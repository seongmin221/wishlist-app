package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Error code of a repository step that ended in a cancellation the Presenter did not ask for (C5). */
internal const val CATEGORY_STEP_FAILURE = "CATEGORY_STEP_FAILURE"

/**
 * The C2/C4 Presenter execution model, shared by the C5 category Presenters:
 *
 * - Intents return at once; their handling, the session observer and every state publication run in
 *   order on a single-lane view of [dispatcher]. Repository calls run on [dispatcher] itself.
 * - Before each intent the lane syncs with the current session, so an account change the observer has
 *   not seen yet is handled first ([onSessionChanged]).
 * - [request] publishes only while its job is still active and only inside
 *   [AuthSession.withCurrent] for the snapshot it started under: a superseded request (its job was
 *   cancelled) or one from a previous session never reaches the state.
 * - A real cancellation propagates; a stray CancellationException from a repository while the job is
 *   active becomes UNAVAILABLE / [CATEGORY_STEP_FAILURE].
 * - [close] cancels everything, is idempotent, and later intents are ignored.
 */
abstract class SessionLane internal constructor(
    private val session: AuthSession,
    private val dispatcher: CoroutineDispatcher,
) : Presenter {
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))

    /** The session this Presenter's state belongs to. Confined to the lane. */
    internal var observed: SessionSnapshot = session.state.value
        private set

    init {
        scope.launch { session.state.collect { sync(it) } }
    }

    /** Called on the lane when the account or generation changed; reset state and cancel requests here. */
    internal abstract fun onSessionChanged(current: SessionSnapshot)

    internal fun intent(block: suspend () -> Unit): Job = scope.launch {
        sync(session.state.value)
        block()
    }

    private fun sync(current: SessionSnapshot) {
        if (current == observed) return
        observed = current
        onSessionChanged(current)
    }

    internal fun <T> request(call: suspend () -> ClientResult<T>, publish: (ClientResult<T>) -> Unit): Job {
        val snapshot = observed
        return scope.launch {
            val result = try {
                withContext(dispatcher) { call() }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // a real cancel propagates
                ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, CATEGORY_STEP_FAILURE))
            }
            session.withCurrent(snapshot) {
                // The lane is single-threaded: nothing can cancel this job between the check and the publish.
                if (currentCoroutineContext().isActive) publish(result)
                ClientResult.Success(Unit)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
