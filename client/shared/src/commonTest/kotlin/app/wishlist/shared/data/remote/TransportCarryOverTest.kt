@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Task 6a review carry-overs fixed in Task 6b. */
class TransportCarryOverTest {
    private val session = MutableAuthSession()
    private val tokenSource = FakeTokenSource()
    private val tokens = CallbackAuthTokenProvider(session, tokenSource)

    private suspend fun accountA(): SessionSnapshot {
        session.changeAccount("account-a")
        return session.state.value
    }

    @Test fun generated_key_and_body_are_identical_on_the_401_resend() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("old", "fresh")
        var statuses = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Created)
        val engine = MockEngine { json(statuses.first().also { statuses = statuses.drop(1) }, "{}") }
        var builds = 0
        val result = transportOf(session, tokens, engine).execute(snapshot, ApiId.ITEM_01) {
            builds++
            method = HttpMethod.Post
            url("/v1/wishlist-items")
            header("Idempotency-Key", Uuid.random().toString()) // generated, not a constant
            setBody(TextContent("""{"nonce":"${Uuid.random()}"}""", ContentType.Application.Json))
        }
        assertTrue(result is ClientResult.Success)
        val (first, second) = engine.requestHistory
        assertEquals(1, builds)
        assertNotEquals(null, first.headers["Idempotency-Key"])
        assertEquals(first.headers["Idempotency-Key"], second.headers["Idempotency-Key"])
        assertEquals(first.bodyText(), second.bodyText())
        assertEquals("Bearer old", first.authorization())
        assertEquals("Bearer fresh", second.authorization())
    }

    @Test fun cancelled_caller_is_not_reported_as_network_failure() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("token-a")
        lateinit var caller: kotlinx.coroutines.Job
        val engine = MockEngine { json(HttpStatusCode.OK, "{}") }
        val client = createWishlistHttpClient(engine)
        // Cancel the caller, then fail with a plain (non-cancellation) error, like a closed socket.
        client.plugin(HttpSend).intercept {
            caller.cancel()
            throw IllegalStateException("socket closed")
        }
        val transport = AuthenticatedTransport(session, tokens, client, TEST_BASE_URL)
        var result: ClientResult<*>? = null
        var propagated = false
        caller = launch {
            try {
                result = transport.get(snapshot)
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }
        caller.join()
        assertTrue(propagated)
        assertNull(result)
    }

    /** Returns a token without checking the session, like a careless implementation would. */
    private inner class NonCheckingTokens(private val changeOnCall: Int) : AuthTokenProvider {
        var calls = 0
        override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> {
            if (++calls == changeOnCall) session.changeAccount("account-b")
            return ClientResult.Success("tok-$calls")
        }
    }

    @Test fun transport_checks_staleness_itself_after_the_first_token() = runTest {
        val snapshot = accountA()
        val engine = MockEngine { json(HttpStatusCode.OK, "{}") }
        val result = transportOf(session, NonCheckingTokens(changeOnCall = 1), engine).create(snapshot)
        assertEquals(ErrorKind.SESSION_CHANGED, result.failureKind())
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test fun transport_checks_staleness_itself_before_the_resend() = runTest {
        val snapshot = accountA()
        val engine = MockEngine { json(HttpStatusCode.Unauthorized, "{}") }
        val result = transportOf(session, NonCheckingTokens(changeOnCall = 2), engine).create(snapshot)
        assertEquals(ErrorKind.SESSION_CHANGED, result.failureKind())
        assertEquals(1, engine.requestHistory.size) // the stale request is never re-sent
    }
}
