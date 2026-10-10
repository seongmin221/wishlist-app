package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.canonicalUuidOrNull
import app.wishlist.shared.repository.GetItemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Error code of a repository step that ended in a cancellation the Presenter did not ask for. */
internal const val DETAIL_STEP_FAILURE = "DETAIL_STEP_FAILURE"

/**
 * ITEM-03 detail lookup (C4 product detail).
 *
 * [repository] is the only item dependency; cache sync belongs to the Get facade's decorator.
 * Intents may arrive on any thread (normally the UI thread) and return at once. Their handling,
 * the session observer and every state publication are serialized on a single-lane view of
 * [dispatcher]; repository calls run on [dispatcher] itself, never on the caller's thread.
 *
 * - IDs are normalized to the canonical lowercase UUID form before use (a non-UUID is kept as
 *   given), so the shown item survives a refresh whatever casing the caller passed.
 * - Last request wins: a new load/retry/refresh cancels the previous request, and a late answer
 *   whose request ID is no longer current is dropped.
 * - [refresh] and [retry] repeat the last ID and keep the shown item while loading; a general
 *   error keeps it, NOT_FOUND removes it. Before the first load they do nothing.
 * - An account or generation change (same-account relogin included) cancels the request, forgets
 *   the last ID (D3: a previous account's item is never requested again, so retry/refresh then do
 *   nothing) and resets to [ItemDetailState.Initial]. Each answer is published only inside
 *   [AuthSession.withCurrent] for the snapshot it was requested under, so no answer from a
 *   previous session is ever published.
 * - [onLoaded] runs on the lane after each success or NOT_FOUND state is published (the runtime uses
 *   it to republish the submission view from the refreshed cache; NOT_FOUND already dropped the row).
 *
 * @param onLoaded called on the lane inside the session gate ([AuthSession.withCurrent]) right after
 *   a success or NOT_FOUND state is published, so it must be non-blocking and must not throw. An
 *   answer that was superseded by a newer request (or a session change) is never published and does not call it.
 * - [close] cancels everything, is idempotent, and later intents are ignored.
 * - A real cancellation (this request was replaced, the session changed, or [close]) propagates
 *   and is never an error state. A stray CancellationException from the repository while this
 *   request is still active becomes UNAVAILABLE / [DETAIL_STEP_FAILURE].
 */
class ItemDetailPresenter(
    private val repository: GetItemRepository,
    private val session: AuthSession,
    private val dispatcher: CoroutineDispatcher,
    private val onLoaded: () -> Unit = {},
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
        scope.launch { start(canonicalUuidOrNull(id) ?: id) }
    }

    /** Tries the last load again after an error; same as [refresh]. */
    fun retry() {
        scope.launch { repeatLast() }
    }

    /**
     * Repeats the last load, keeping the shown item while loading. Does nothing before the first
     * load or after an account change.
     */
    fun refresh() {
        scope.launch { repeatLast() }
    }

    override fun close() {
        scope.cancel()
    }

    private fun repeatLast() {
        // Sync first: an account change the observer has not seen yet must clear lastId before it is read (D3).
        followSession(session.state.value)
        lastId?.let { start(it) }
    }

    private fun followSession(current: SessionSnapshot) {
        if (current == observedSession) return
        observedSession = current
        invalidate()
        lastId = null // D3: a previous account's item is never requested again
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
            val result = try {
                withContext(dispatcher) { repository.get(id) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // a real cancel propagates
                ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, DETAIL_STEP_FAILURE))
            }
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
                    if (result is ClientResult.Success ||
                        (result is ClientResult.Failure && result.error.kind == ErrorKind.NOT_FOUND)
                    ) onLoaded()
                }
                ClientResult.Success(Unit)
            }
        }
    }
}
