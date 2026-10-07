package app.wishlist.shared.data.remote

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.PlatformTokenSource
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.TokenCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Adapts the platform's token/error callback to a suspend call. Never wraps the wait in the
 * session gate. The first callback wins; later or duplicate completions are ignored. Cancelling
 * the caller cancels the platform request exactly once.
 */
internal class CallbackAuthTokenProvider(
    private val session: AuthSession,
    private val source: PlatformTokenSource,
) : AuthTokenProvider {
    private data class Completion(val token: String?, val errorCode: String?)

    override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> {
        if (session.state.value != snapshot) return sessionChanged()
        val completion = CompletableDeferred<Completion>()
        val request = source.fetchToken(forceRefresh, object : TokenCallback {
            // complete() on an already completed deferred is a no-op: late/duplicate calls vanish.
            override fun complete(token: String?, errorCode: String?) {
                completion.complete(Completion(token, errorCode))
            }
        })
        val result = try {
            completion.await()
        } catch (e: CancellationException) {
            if (!completion.isCompleted) request.cancel()
            throw e
        }
        if (session.state.value != snapshot) return sessionChanged()
        val token = result.token
        return if (token.isNullOrBlank()) {
            ClientResult.Failure(ClientError(ErrorKind.UNAUTHENTICATED, code = result.errorCode))
        } else {
            ClientResult.Success(token)
        }
    }

    private fun sessionChanged() = ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))
}
