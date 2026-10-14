package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.canonicalUuidOrNull
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.ItemListRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the chip already knew; draws the header before any request (and after a process restore). */
data class CategoryListHint(
    val categoryId: String,
    val name: String,
    val parentId: String,
    val parentName: String,
    val kind: CategoryKind,
)

/** [count] is CAT-01/02 itemCount when known, else ITEM-02 totalCount (the same display set), else null. */
data class CategoryListHeader(val name: String, val parentName: String, val kind: CategoryKind, val count: Int?)

/**
 * - [custom]: CAT-02 detail (custom only), the edit form's starting point.
 * - [itemsError]: the first page failed (header stays). [appendFailed]: the next page failed; the
 *   list keeps what it has and the end of the list offers a retry.
 * - [partialFailed]: a pull-to-refresh got the items but not the header (a short notice).
 * - [gone]: the custom category no longer exists (CATEGORY_NOT_FOUND): "삭제된 카테고리예요".
 */
data class CategoryListState(
    val header: CategoryListHeader?,
    val custom: CustomCategoryDetail?,
    val items: List<WishlistItem>,
    val totalCount: Int?,
    val hasMore: Boolean,
    val loading: Boolean,
    val appending: Boolean,
    val appendFailed: Boolean,
    val itemsError: ClientError?,
    val partialFailed: Boolean,
    val gone: Boolean,
) {
    companion object {
        val Initial = CategoryListState(null, null, emptyList(), null, false, false, false, false, null, false, false)
    }
}

/**
 * One category's item list (FCategoryList / ListCustom / ListCustomEmpty). The header (CAT-01 BROWSE
 * of the parent; CAT-02 for a custom) and the ITEM-02 pages are separate requests that succeed or
 * fail on their own.
 *
 * Intents: [load], [loadMore] (end of list; ignored while a page is in flight or none is left),
 * [refresh] (header + first page again; appended pages are dropped until C7 anchors), [refreshNow],
 * [refreshHeader] (header only: items, cursor and laid-out cards stay), [applyEdited] (the CAT-04
 * answer's name right away, then [refreshHeader]), [retry] (only what failed).
 * A session change cancels everything and resets (the shell closes this account-scoped screen).
 */
class CategoryListPresenter internal constructor(
    private val categories: CategoryRepository,
    private val items: ItemListRepository,
    session: AuthSession,
    dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutableState = MutableStateFlow(CategoryListState.Initial)
    val state: StateFlow<CategoryListState> = mutableState.asStateFlow()

    // Lane-confined.
    private var hint: CategoryListHint? = null
    private var cursor: String? = null
    private var headerCount: Int? = null
    private var headerFailed = false
    private var headerJob: Job? = null
    private var pageJob: Job? = null
    private var moreJob: Job? = null

    fun load(hint: CategoryListHint) {
        intent {
            val normalized = hint.copy(categoryId = canonicalUuidOrNull(hint.categoryId) ?: hint.categoryId)
            if (this.hint == normalized && mutableState.value.header != null) return@intent
            cancelAll()
            this.hint = normalized
            headerCount = null
            mutableState.value = CategoryListState.Initial.copy(
                header = CategoryListHeader(normalized.name, normalized.parentName, normalized.kind, null), loading = true,
            )
            fetchHeader(userRefresh = false)
            fetchFirstPage()
        }
    }

    fun loadMore() {
        intent {
            val next = cursor ?: return@intent
            val now = mutableState.value
            if (now.appending || now.loading || pageJob != null || now.gone) return@intent
            val id = hint?.categoryId ?: return@intent
            mutableState.value = now.copy(appending = true, appendFailed = false)
            moreJob = request({ items.byCategory(id, next) }) { result ->
                moreJob = null
                when (result) {
                    is ClientResult.Success -> {
                        val known = mutableState.value.items.mapTo(hashSetOf()) { it.id }
                        cursor = result.value.nextCursor
                        mutableState.value = withCount(mutableState.value.copy(
                            items = mutableState.value.items + result.value.items.filter { it.id !in known },
                            totalCount = result.value.totalCount, hasMore = cursor != null, appending = false,
                        ))
                    }
                    is ClientResult.Failure -> mutableState.value =
                        if (isGone(result.error)) gone() else mutableState.value.copy(appending = false, appendFailed = true)
                }
            }
        }
    }

    fun refresh() { intent { refreshAll() } }

    /** [refresh], awaited until both requests ended (iOS `.refreshable`). */
    suspend fun refreshNow() {
        var jobs: List<Job?> = emptyList()
        intent { refreshAll(); jobs = listOf(headerJob, pageJob) }.join()
        jobs.forEach { it?.join() }
    }

    fun refreshHeader() { intent { if (hint != null && !mutableState.value.gone) fetchHeader(userRefresh = false, quiet = true) } }

    fun applyEdited(detail: CustomCategoryDetail) {
        intent {
            val current = hint ?: return@intent
            if (current.categoryId != detail.id || mutableState.value.gone) return@intent
            hint = current.copy(name = detail.name)
            headerCount = detail.itemCount
            mutableState.value = withCount(mutableState.value.copy(
                custom = detail, header = mutableState.value.header?.copy(name = detail.name),
            ))
            fetchHeader(userRefresh = false, quiet = true)
        }
    }

    fun retry() {
        intent {
            if (hint == null || mutableState.value.gone) return@intent
            if (headerFailed && headerJob == null) fetchHeader(userRefresh = false)
            when {
                mutableState.value.itemsError != null && pageJob == null -> {
                    mutableState.value = mutableState.value.copy(itemsError = null, loading = true)
                    fetchFirstPage()
                }
                mutableState.value.appendFailed -> {
                    mutableState.value = mutableState.value.copy(appendFailed = false)
                    loadMore()
                }
            }
        }
    }

    override fun onSessionChanged(current: SessionSnapshot) {
        cancelAll()
        hint = null
        mutableState.value = CategoryListState.Initial
    }

    private fun refreshAll() {
        if (hint == null || mutableState.value.gone) return
        moreJob?.cancel(); moreJob = null
        mutableState.value = mutableState.value.copy(loading = true, appending = false, appendFailed = false, partialFailed = false, itemsError = null)
        fetchHeader(userRefresh = true)
        fetchFirstPage()
    }

    private fun cancelAll() {
        listOf(headerJob, pageJob, moreJob).forEach { it?.cancel() }
        headerJob = null; pageJob = null; moreJob = null
        cursor = null
        headerFailed = false
    }

    private fun fetchFirstPage() {
        val id = hint?.categoryId ?: return
        pageJob?.cancel()
        pageJob = request({ items.byCategory(id) }) { result ->
            pageJob = null
            when (result) {
                is ClientResult.Success -> {
                    cursor = result.value.nextCursor
                    mutableState.value = withCount(mutableState.value.copy(
                        items = result.value.items, totalCount = result.value.totalCount, hasMore = cursor != null,
                        loading = false, itemsError = null, appendFailed = false,
                    ))
                }
                is ClientResult.Failure -> mutableState.value =
                    if (isGone(result.error)) gone() else mutableState.value.copy(loading = false, itemsError = result.error)
            }
        }
    }

    /** [userRefresh]: a failure shows [CategoryListState.partialFailed]. [quiet]: a failure changes nothing. */
    private fun fetchHeader(userRefresh: Boolean, quiet: Boolean = false) {
        val h = hint ?: return
        headerJob?.cancel()
        headerJob = if (h.kind == CategoryKind.CUSTOM) {
            request({ categories.custom(h.categoryId) }) { result ->
                headerJob = null
                when (result) {
                    is ClientResult.Success -> {
                        headerFailed = false
                        hint = h.copy(name = result.value.name)
                        headerCount = result.value.itemCount
                        mutableState.value = withCount(mutableState.value.copy(
                            custom = result.value, header = CategoryListHeader(result.value.name, h.parentName, h.kind, null),
                        ))
                    }
                    is ClientResult.Failure -> onHeaderFailure(result.error, userRefresh, quiet)
                }
            }
        } else {
            request({ categories.catalog(CategoryScope.BROWSE, h.parentId) }) { result ->
                headerJob = null
                when (result) {
                    is ClientResult.Success -> {
                        headerFailed = false
                        val group = result.value.groups.firstOrNull { it.id == h.parentId }
                        val found = group?.categories?.firstOrNull { it.id == h.categoryId }
                        // A public leaf with no visible item leaves BROWSE: keep the hint, count from ITEM-02.
                        headerCount = found?.itemCount
                        val name = found?.name ?: h.name
                        hint = h.copy(name = name, parentName = group?.name ?: h.parentName)
                        mutableState.value = withCount(mutableState.value.copy(
                            header = CategoryListHeader(name, group?.name ?: h.parentName, found?.kind ?: h.kind, null),
                        ))
                    }
                    is ClientResult.Failure -> onHeaderFailure(result.error, userRefresh, quiet)
                }
            }
        }
    }

    private fun onHeaderFailure(error: ClientError, userRefresh: Boolean, quiet: Boolean) {
        if (isGone(error)) { mutableState.value = gone(); return }
        if (quiet) return
        headerFailed = true
        if (userRefresh) mutableState.value = mutableState.value.copy(partialFailed = true)
    }

    private fun withCount(state: CategoryListState): CategoryListState =
        state.copy(header = state.header?.copy(count = headerCount ?: state.totalCount))

    private fun isGone(error: ClientError) = error.kind == ErrorKind.NOT_FOUND && error.code == "CATEGORY_NOT_FOUND"

    private fun gone(): CategoryListState {
        cancelAll()
        return mutableState.value.copy(gone = true, loading = false, appending = false)
    }
}
