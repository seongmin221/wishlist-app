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
)
class PurposeDetailPresenter internal constructor(
    private val purposes: PurposeRepository, private val items: ItemListRepository,
    session: AuthSession, dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutable = MutableStateFlow(PurposeDetailState())
    val state: StateFlow<PurposeDetailState> = mutable.asStateFlow()
    private var id: String? = null
    private var cursor: String? = null
    private var headerJob: Job? = null
    private var pageJob: Job? = null
    private var appendJob: Job? = null

    fun load(id: String) { intent {
        if (this.id == id) return@intent
        cancelAll()
        this.id = id
        mutable.value = PurposeDetailState()
        fetchHeader(); fetchPage()
    } }
    fun refresh() { intent { refreshAll() } }
    suspend fun refreshNow() {
        var jobs: List<Job?> = emptyList()
        intent { refreshAll(); jobs = listOf(headerJob, pageJob) }.join()
        jobs.forEach { it?.join() }
    }
    private fun refreshAll() {
        if (id == null || mutable.value.gone) return
        appendJob?.cancel(); appendJob = null
        mutable.value = mutable.value.copy(appending = false, appendFailed = false)
        fetchHeader(); fetchPage()
    }
    fun loadMore() { intent {
        val next = cursor ?: return@intent
        val target = id ?: return@intent
        if (mutable.value.gone || pageJob != null || appendJob != null) return@intent
        mutable.value = mutable.value.copy(appending = true, appendFailed = false)
        appendJob = request({ items.byPurpose(target, next) }) { result ->
            appendJob = null
            when (result) {
                is ClientResult.Success -> {
                    cursor = result.value.nextCursor
                    val known = mutable.value.items.mapTo(hashSetOf()) { it.id }
                    mutable.value = mutable.value.copy(
                        items = mutable.value.items + result.value.items.filter { it.id !in known },
                        hasMore = cursor != null, appending = false,
                    )
                }
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else { mutable.value = mutable.value.copy(appending = false, appendFailed = true) }
            }
        }
    } }
    fun retry() { intent {
        if (mutable.value.gone || id == null) return@intent
        if (mutable.value.error != null && headerJob == null) fetchHeader()
        if (mutable.value.itemsError != null && pageJob == null) fetchPage()
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
    private fun fetchHeader() {
        val target = id ?: return
        headerJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        headerJob = request({ purposes.detail(target) }) { result ->
            headerJob = null
            when (result) {
                is ClientResult.Success -> mutable.value = mutable.value.copy(detail = reconcile(result.value), loading = false)
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else { mutable.value = mutable.value.copy(loading = false, error = result.error) }
            }
        }
    }
    private fun fetchPage() {
        val target = id ?: return
        pageJob?.cancel()
        mutable.value = mutable.value.copy(itemsLoading = true, itemsError = null)
        pageJob = request({ items.byPurpose(target) }) { result ->
            pageJob = null
            when (result) {
                is ClientResult.Success -> {
                    cursor = result.value.nextCursor
                    mutable.value = mutable.value.copy(items = result.value.items, itemsLoading = false, hasMore = cursor != null)
                }
                is ClientResult.Failure -> if (isGone(result.error)) gone()
                    else { mutable.value = mutable.value.copy(itemsLoading = false, itemsError = result.error) }
            }
        }
    }
    private fun isGone(error: ClientError) = error.code == "PURPOSE_NOT_FOUND"
    private fun gone() {
        cancelAll()
        mutable.value = PurposeDetailState(gone = true)
    }
    private fun cancelAll() {
        headerJob?.cancel(); pageJob?.cancel(); appendJob?.cancel()
        headerJob = null; pageJob = null; appendJob = null; cursor = null
    }
}
