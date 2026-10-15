package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.CategoryCatalog
import app.wishlist.shared.model.CategoryGroup
import app.wishlist.shared.model.CategoryScope
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Category tab first screen (FCategoryHome): CAT-01 BROWSE groups, one rail page per group.
 *
 * - [signedIn] false: no request at all (D5: the tab shows the sign-in prompt).
 * - [groups] empty while signed in and loaded: nothing saved and no custom category (D6).
 * - [currentGroupId]: the page shown. It starts on the last group this account looked at (kept
 *   per account in app state) when that group is still in the list, otherwise the first group.
 * - [error]: the first load failed (nothing to show). [refreshFailed]: a refresh failed while
 *   the previous groups stay on screen.
 */
data class CategoryHomeState(
    val signedIn: Boolean,
    val loaded: Boolean,
    val groups: List<CategoryGroup>,
    val totalCount: Int,
    val currentGroupId: String?,
    val customUsedCount: Int,
    val customLimit: Int,
    val loading: Boolean,
    val error: ClientError?,
    val refreshFailed: Boolean,
) {
    companion object {
        val Initial = CategoryHomeState(false, false, emptyList(), 0, null, 0, 0, loading = false, error = null, refreshFailed = false)
    }
}

/** The app-state key of an account's last category group. */
internal fun lastCategoryGroupKey(accountId: String) = "category.lastGroup.$accountId"

/**
 * Intents: [load] (tab entry), [refresh] (pull to refresh, foreground, after a create or edit),
 * [refreshNow] (awaited refresh for iOS `.refreshable`), [retry], [showGroup].
 * A session change cancels the request and resets; a signed-in session then loads again if the
 * screen has loaded before (sign-in while the tab is open shows that account's groups).
 */
class CategoryHomePresenter internal constructor(
    private val categories: CategoryRepository,
    private val store: LocalStore,
    session: AuthSession,
    private val dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutableState = MutableStateFlow(CategoryHomeState.Initial.copy(signedIn = session.state.value.accountId != null))
    val state: StateFlow<CategoryHomeState> = mutableState.asStateFlow()

    // Lane-confined.
    private var started = false
    private var inFlight: Job? = null
    private var savedGroup: String? = null
    private var savedGroupRead = false
    /** A session change already queued this account's load; an intent that synced it must not fetch again. */
    private var sessionFetchQueued = false

    fun load() { intent { started = true; fetch(keepShown = false) } }
    fun refresh() { intent { if (started && !sessionFetchQueued) fetch(keepShown = true) } }
    fun retry() { intent { if (started && !sessionFetchQueued) fetch(keepShown = mutableState.value.groups.isNotEmpty()) } }

    /** [refresh], awaited until that request ended (returns at once before the first load or after close). */
    suspend fun refreshNow() {
        var job: Job? = null
        intent { if (started && !sessionFetchQueued) { fetch(keepShown = true); job = inFlight } }.join()
        job?.join()
    }

    /** The pager or the rail moved to [groupId]; remembered for this account (only when it changes). */
    fun showGroup(groupId: String) { intent {
        val current = mutableState.value
        if (current.currentGroupId == groupId || current.groups.none { it.id == groupId }) return@intent
        mutableState.value = current.copy(currentGroupId = groupId)
        val account = observed.accountId ?: return@intent
        savedGroup = groupId
        savedGroupRead = true
        withContext(dispatcher) { store.writeAppState(lastCategoryGroupKey(account), groupId) }
    } }

    override fun onSessionChanged(current: SessionSnapshot) {
        inFlight?.cancel()
        inFlight = null
        savedGroup = null
        savedGroupRead = false
        mutableState.value = CategoryHomeState.Initial.copy(signedIn = current.accountId != null)
        if (started && current.accountId != null) {
            sessionFetchQueued = true
            scope.launch { sessionFetchQueued = false; fetch(keepShown = false) }
        }
    }

    private suspend fun fetch(keepShown: Boolean) {
        val account = observed.accountId
        if (account == null) {
            mutableState.value = CategoryHomeState.Initial.copy(loaded = true)
            return
        }
        if (!savedGroupRead) {
            val snapshot = observed
            val read = (withContext(dispatcher) { store.readAppState(lastCategoryGroupKey(account)) } as? ClientResult.Success)?.value
            // The lane was free while reading: a session change since then already restarted the load.
            if (observed != snapshot) return
            savedGroup = read
            savedGroupRead = true
        }
        inFlight?.cancel() // after the read: the newest fetch is the one that requests last
        val before = mutableState.value
        mutableState.value = before.copy(signedIn = true, loading = true, error = null, refreshFailed = false)
        inFlight = request({ categories.catalog(CategoryScope.BROWSE) }) { result ->
            inFlight = null
            mutableState.value = when (result) {
                is ClientResult.Success -> loaded(result.value, mutableState.value.currentGroupId)
                is ClientResult.Failure ->
                    if (keepShown && mutableState.value.loaded) mutableState.value.copy(loading = false, refreshFailed = true)
                    else mutableState.value.copy(loading = false, error = result.error)
            }
        }
    }

    private fun loaded(catalog: CategoryCatalog, shown: String?): CategoryHomeState {
        val ids = catalog.groups.map { it.id }
        val current = listOf(shown, savedGroup).firstOrNull { it != null && it in ids } ?: ids.firstOrNull()
        return CategoryHomeState(
            signedIn = true, loaded = true, groups = catalog.groups, totalCount = catalog.groups.sumOf { it.itemCount },
            currentGroupId = current, customUsedCount = catalog.customUsedCount, customLimit = catalog.customLimit,
            loading = false, error = null, refreshFailed = false,
        )
    }
}
