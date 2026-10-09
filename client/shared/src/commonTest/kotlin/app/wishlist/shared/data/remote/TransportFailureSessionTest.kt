package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Failure completions must obey the same snapshot guard as successful responses. */
class TransportFailureSessionTest {
    private val session = MutableAuthSession()
    private val tokenFailure = ClientResult.Failure(ClientError(ErrorKind.UNAUTHENTICATED, code = "TOKEN_EXPIRED"))
    private val tokens = object : AuthTokenProvider {
        override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean) = ClientResult.Success("token-a")
    }

    private suspend fun failedSend(failOn: Int, nextAccount: String?): ClientResult<*> {
        session.changeAccount("account-a")
        val snapshot = session.state.value
        val client = createWishlistHttpClient(MockEngine { json(HttpStatusCode.Unauthorized, "{}") })
        var attempts = 0
        client.plugin(HttpSend).intercept { request ->
            if (++attempts == failOn) {
                if (nextAccount != null) session.changeAccount(nextAccount)
                throw IllegalStateException("socket closed")
            }
            execute(request)
        }
        return try {
            AuthenticatedTransport(session, tokens, client, TEST_BASE_URL).get(snapshot)
        } finally {
            client.close()
        }
    }

    @Test fun first_send_failure_after_account_switch_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedSend(1, "account-b").failureKind())
    }

    @Test fun first_send_failure_after_same_account_relogin_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedSend(1, "account-a").failureKind())
    }

    @Test fun resend_failure_after_account_switch_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedSend(2, "account-b").failureKind())
    }

    @Test fun resend_failure_after_same_account_relogin_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedSend(2, "account-a").failureKind())
    }

    @Test fun unchanged_session_preserves_first_send_network_failure() = runTest {
        assertEquals(ErrorKind.NETWORK, failedSend(1, null).failureKind())
    }

    @Test fun unchanged_session_preserves_resend_network_failure() = runTest {
        assertEquals(ErrorKind.NETWORK, failedSend(2, null).failureKind())
    }

    private suspend fun failedToken(failOn: Int, nextAccount: String?): ClientResult<*> {
        session.changeAccount("account-a")
        val snapshot = session.state.value
        var calls = 0
        val failingTokens = object : AuthTokenProvider {
            override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> {
                if (++calls == failOn) {
                    if (nextAccount != null) session.changeAccount(nextAccount)
                    return tokenFailure
                }
                return ClientResult.Success("token-a")
            }
        }
        val client = createWishlistHttpClient(MockEngine { json(HttpStatusCode.Unauthorized, "{}") })
        return try {
            AuthenticatedTransport(session, failingTokens, client, TEST_BASE_URL).get(snapshot)
        } finally {
            client.close()
        }
    }

    @Test fun first_token_failure_after_account_switch_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedToken(1, "account-b").failureKind())
    }

    @Test fun refresh_token_failure_after_same_account_relogin_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, failedToken(2, "account-a").failureKind())
    }

    @Test fun unchanged_session_preserves_token_failure_details() = runTest {
        assertEquals(tokenFailure, failedToken(1, null))
        assertEquals(tokenFailure, failedToken(2, null))
    }

    private suspend fun readBody(status: HttpStatusCode, nextAccount: String?, failure: Exception? = null): ClientResult<*> {
        session.changeAccount("account-a")
        val client = createWishlistHttpClient(MockEngine { json(status, "{}") })
        client.responsePipeline.intercept(HttpResponsePipeline.Receive) {
            if (nextAccount != null) session.changeAccount(nextAccount)
            if (failure != null) throw failure
        }
        val transport = AuthenticatedTransport(session, tokens, client, TEST_BASE_URL)
        return try {
            RemoteItemRepository(session, transport).get("item-id")
        } finally {
            client.close()
        }
    }

    @Test fun session_change_during_http_error_body_mapping_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, readBody(HttpStatusCode.InternalServerError, "account-b").failureKind())
    }

    @Test fun session_change_during_success_body_failure_is_session_changed() = runTest {
        assertEquals(ErrorKind.SESSION_CHANGED, readBody(HttpStatusCode.OK, "account-a", IllegalStateException("body failed")).failureKind())
    }

    @Test fun unchanged_session_preserves_success_body_network_failure() = runTest {
        assertEquals(ErrorKind.NETWORK, readBody(HttpStatusCode.OK, null, IllegalStateException("body failed")).failureKind())
    }

    @Test fun body_cancellation_propagates_after_account_switch() = runTest {
        assertFailsWith<CancellationException> {
            readBody(HttpStatusCode.OK, "account-b", CancellationException("cancelled"))
        }
    }
}
