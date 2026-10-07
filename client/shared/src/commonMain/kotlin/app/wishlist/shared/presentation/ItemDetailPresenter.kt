package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.repository.GetItemRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ITEM-03 detail lookup foundation that C4 extends with the detail intents.
 *
 * [repository] is the only item dependency; cache sync belongs to the Get facade's decorator.
 * Intents may arrive on any thread (normally the UI thread) and return at once. Their handling,
 * the session observer and every state publication are serialized on a single-lane view of
 * [dispatcher]; repository calls run on [dispatcher] itself, never on the caller's thread.
 *
 * - Last request wins: a new load/retry cancels the previous request, and a late answer whose
 *   request ID is no longer current is dropped.
 * - A refresh of the shown item keeps it on a general error; NOT_FOUND removes it.
 * - An account or generation change (same-account relogin included) cancels the request and
 *   resets to [ItemDetailState.Initial]. Each answer is published only inside
 *   [AuthSession.withCurrent] for the snapshot it was requested under, so no answer from a
 *   previous session is ever published.
 * - [close] cancels everything, is idempotent, and later intents are ignored.
 * - CancellationException propagates; it is never turned into an error state.
 */
class ItemDetailPresenter(
    private val repository: GetItemRepository,
    private val session: AuthSession,
    private val dispatcher: CoroutineDispatcher,
) : Presenter {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val mutableState = MutableStateFlow(ItemDetailState.Initial)

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<ItemDetailState> = mutableState.asStateFlow()

    // Confined to the serial lane after construction.
    private var observedSession: SessionSnapshot = session.state.value
    private var lastId: String? = null
    private var requestId = 0L
    private var inFlight: Job? = null

    init {
        scope.launch { session.state.collect { followSession(it) } }
    }

    /** Loads [id]; keeps the shown item while refreshing the same ID. */
    fun load(id: String) {
        scope.launch { start(id) }
    }

    /** Repeats the last load (also after an error); does nothing before the first load. */
    fun retry() {
        scope.launch { lastId?.let { start(it) } }
    }

    override fun close() {
        scope.cancel()
    }

    private fun followSession(current: SessionSnapshot) {
        if (current == observedSession) return
        observedSession = current
        invalidate()
        mutableState.value = ItemDetailState.Initial
    }

    private fun invalidate() {
        requestId++
        inFlight?.cancel()
        inFlight = null
    }

    private fun start(id: String) {
        // An account change the observer has not seen yet must not cancel this new request later.
        followSession(session.state.value)
        invalidate()
        val request = requestId
        val snapshot = observedSession
        lastId = id
        val shown = mutableState.value.item?.takeIf { it.id == id }
        mutableState.value = ItemDetailState(item = shown, loading = true, error = null)
        inFlight = scope.launch {
            val result = withContext(dispatcher) { repository.get(id) }
            if (request != requestId) return@launch
            // Publication is a short commit: serialized with account changes, refused when stale.
            session.withCurrent(snapshot) {
                if (request == requestId) {
                    inFlight = null
                    mutableState.value = when (result) {
                        is ClientResult.Success -> ItemDetailState(item = result.value, loading = false, error = null)
                        is ClientResult.Failure -> ItemDetailState(
                            item = if (result.error.kind == ErrorKind.NOT_FOUND) null else mutableState.value.item,
                            loading = false,
                            error = result.error,
                        )
                    }
                }
                ClientResult.Success(Unit)
            }
        }
    }
}
