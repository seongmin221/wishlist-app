package app.wishlist.shared.presentation

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PurposeDetailState(
    val detail: PurposeDetail? = null, val items: List<WishlistItem> = emptyList(),
    val loading: Boolean = false, val itemsLoading: Boolean = false, val hasMore: Boolean = false,
    val appending: Boolean = false, val error: ClientError? = null, val itemsError: ClientError? = null,
    val appendFailed: Boolean = false, val gone: Boolean = false,
    /** Newer candidates in front ([PurposeDetailPresenter.loadNewer]); [restore]: see [AnchorRestore], null after entry. */
    val hasNewer: Boolean = false, val prepending: Boolean = false, val restore: AnchorRestore? = null,
)
/**
 * Intents: [load] (first page), [loadMore], [loadNewer] (previousCursor, in front), [refresh]/[refreshNow]
 * (header + the window around the first visible card's anchor, else the first page; replaces the list
 * and sets [PurposeDetailState.restore]), [foreground] (the same, quietly; ignored while anything is in
 * flight), [retry], [applyEdited]. The no-argument [refresh]/[refreshNow] mean "no anchor".
 */
class PurposeDetailPresenter internal constructor(
    private val purposes: PurposeRepository, private val items: ItemListRepository,
    session: AuthSession, dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutable = MutableStateFlow(PurposeDetailState())
    val state: StateFlow<PurposeDetailState> = mutable.asStateFlow()
    private var id: String? = null
    // Lane-confined.
    private var window = ItemWindow.Empty
    private var restoreToken = 0L
    private var headerJob: Job? = null
    private var pageJob: Job? = null
    private var appendJob: Job? = null
    private var newerJob: Job? = null

    fun load(id: String) { intent {
        if (this.id == id) return@intent
        cancelAll()
        this.id = id
        mutable.value = PurposeDetailState()
        fetchHeader(); fetchPage(anchorItemId = null, restore = false)
    } }
    fun refresh() = refresh(null)
    /** [anchorItemId]: the first visible card's id (null when unknown). */
    fun refresh(anchorItemId: String?) { intent { refreshAll(anchorItemId) } }
    suspend fun refreshNow() = refreshNow(null)
    suspend fun refreshNow(anchorItemId: String?) {
        var jobs: List<Job?> = emptyList()
        intent { refreshAll(anchorItemId); jobs = listOf(headerJob, pageJob) }.join()
        jobs.forEach { it?.join() }
    }
    fun foreground(anchorItemId: String?) { intent {
        if (id == null || mutable.value.gone) return@intent
        if (listOf(headerJob, pageJob, appendJob, newerJob).any { it != null }) return@intent
        fetchHeader(quiet = true); fetchPage(anchorItemId, restore = true, quiet = true)
    } }
    private fun refreshAll(anchorItemId: String?) {
        if (id == null || mutable.value.gone) return
        appendJob?.cancel(); appendJob = null
        newerJob?.cancel(); newerJob = null
        mutable.value = mutable.value.copy(appending = false, appendFailed = false, prepending = false)
        fetchHeader(); fetchPage(anchorItemId, restore = true)
    }
    fun loadNewer() { intent {
        val previous = window.previousCursor ?: return@intent
        val target = id ?: return@intent
        if (mutable.value.gone || pageJob != null || newerJob != null) return@intent
        mutable.value = mutable.value.copy(prepending = true)
        newerJob = request({ items.byPurpose(target, previous) }) { result ->
            newerJob = null
            when (result) {
                is ClientResult.Success -> {
                    window = window.prepend(result.value)
                    mutable.value = withWindow(mutable.value.copy(prepending = false))
                }
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else { mutable.value = mutable.value.copy(prepending = false) }
            }
        }
    } }
    fun loadMore() { intent {
        val next = window.nextCursor ?: return@intent
        val target = id ?: return@intent
        if (mutable.value.gone || pageJob != null || appendJob != null) return@intent
        mutable.value = mutable.value.copy(appending = true, appendFailed = false)
        appendJob = request({ items.byPurpose(target, next) }) { result ->
            appendJob = null
            when (result) {
                is ClientResult.Success -> {
                    window = window.append(result.value)
                    mutable.value = withWindow(mutable.value.copy(appending = false))
                }
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else { mutable.value = mutable.value.copy(appending = false, appendFailed = true) }
            }
        }
    } }
    fun retry() { intent {
        if (mutable.value.gone || id == null) return@intent
        if (mutable.value.error != null && headerJob == null) fetchHeader()
        if (mutable.value.itemsError != null && pageJob == null) fetchPage(anchorItemId = null, restore = false)
        if (mutable.value.appendFailed) loadMore()
    } }
    fun applyEdited(detail: PurposeDetail) { intent {
        if (detail.id == id && !mutable.value.gone) {
            // Keep an in-flight read: it may know newer membership than this edit/recovery response.
            mutable.value = mutable.value.copy(detail = reconcile(detail), error = null, loading = headerJob != null)
        }
    } }
    override fun onSessionChanged(current: SessionSnapshot) {
        cancelAll(); id = null
        mutable.value = PurposeDetailState()
    }
    /** B3 counters are independent: metadata edits never advance membership, and vice versa. */
    private fun reconcile(incoming: PurposeDetail): PurposeDetail {
        val current = mutable.value.detail ?: return incoming
        val metadata = if (incoming.version >= current.version) incoming else current
        val membership = if (incoming.membershipVersion >= current.membershipVersion) incoming else current
        // updatedAt belongs to metadata edits; candidate transitions change activity instead.
        return metadata.copy(
            candidateCount = membership.candidateCount,
            membershipVersion = membership.membershipVersion,
            activity = membership.activity,
            allowedActions = membership.allowedActions,
        )
    }
    /** [quiet] (foreground): no loading state, and a failure other than gone changes nothing. */
    private fun fetchHeader(quiet: Boolean = false) {
        val target = id ?: return
        headerJob?.cancel()
        if (!quiet) mutable.value = mutable.value.copy(loading = true, error = null)
        headerJob = request({ purposes.detail(target) }) { result ->
            headerJob = null
            when (result) {
                is ClientResult.Success -> mutable.value = mutable.value.copy(detail = reconcile(result.value), loading = false, error = null)
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else if (!quiet) { mutable.value = mutable.value.copy(loading = false, error = result.error) }
            }
        }
    }
    /** Replaces the list: the window around [anchorItemId]'s anchor, else the first page (see [fetchHeader] for [quiet]). */
    private fun fetchPage(anchorItemId: String?, restore: Boolean, quiet: Boolean = false) {
        val target = id ?: return
        val anchor = anchorItemId?.let(window::anchorOf)
        pageJob?.cancel()
        if (!quiet) mutable.value = mutable.value.copy(itemsLoading = true, itemsError = null)
        pageJob = request({ if (anchor != null) items.byPurposeAround(target, anchor) else items.byPurpose(target) }) { result ->
            pageJob = null
            when (result) {
                is ClientResult.Success -> {
                    window = window.replace(result.value)
                    val page = result.value
                    mutable.value = withWindow(mutable.value.copy(
                        itemsLoading = false, itemsError = null,
                        restore = if (!restore) mutable.value.restore else AnchorRestore(
                            resolvedItemId = if (anchor != null) page.anchor?.resolvedItemId else null,
                            sameItem = anchor != null && page.anchor?.resolved == true,
                            token = ++restoreToken,
                        ),
                    ))
                }
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else if (!quiet) { mutable.value = mutable.value.copy(itemsLoading = false, itemsError = result.error) }
            }
        }
    }
    private fun withWindow(state: PurposeDetailState) = state.copy(
        items = window.items, hasMore = window.nextCursor != null, hasNewer = window.previousCursor != null,
    )
    private fun isGone(error: ClientError) = error.code == "PURPOSE_NOT_FOUND"
    private fun gone() {
        cancelAll()
        mutable.value = PurposeDetailState(gone = true)
    }
    private fun cancelAll() {
        headerJob?.cancel(); pageJob?.cancel(); appendJob?.cancel(); newerJob?.cancel()
        headerJob = null; pageJob = null; appendJob = null; newerJob = null; window = ItemWindow.Empty
    }
}
