package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HttpClientTest {
    private val session = MutableAuthSession()
    private val tokenSource = FakeTokenSource()
    private val tokens = CallbackAuthTokenProvider(session, tokenSource)

    private suspend fun accountA(): SessionSnapshot {
        session.changeAccount("account-a")
        return session.state.value
    }

    private fun transport(engine: MockEngine) = transportOf(session, tokens, engine)

    private fun statusEngine(vararg statuses: HttpStatusCode, body: String = "{}") = run {
        var index = 0
        MockEngine { json(statuses[minOf(index++, statuses.lastIndex)], body) }
    }

    @Test
    fun attachesBearerForSameOriginV1Request() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("token-1")
        val engine = statusEngine(HttpStatusCode.OK)
        assertTrue(transport(engine).create(snapshot) is ClientResult.Success)
        assertEquals("Bearer token-1", engine.requestHistory.single().authorization())
        assertEquals("$TEST_BASE_URL/v1/wishlist-items", engine.requestHistory.single().url.toString())
    }

    @Test
    fun noBearerAndNoTokenFetchForOtherOriginOrNonV1Path() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("token-1")
        val engine = statusEngine(HttpStatusCode.OK)
        val transport = transport(engine)
        listOf(
            "https://evil.example.test/v1/wishlist-items", // other host
            "http://api.example.test/v1/wishlist-items", // other scheme
            "https://api.example.test:8443/v1/wishlist-items", // other port
            "/healthz", // not /v1
            "/v10/wishlist-items", // /v1 prefix but different segment
            "/media/v1/x",
        ).forEach { assertTrue(transport.create(snapshot, it) is ClientResult.Success, it) }
        assertEquals(6, engine.requestHistory.size)
        assertTrue(engine.requestHistory.all { it.authorization() == null })
        assertTrue(tokenSource.requests.isEmpty())
    }

    @Test
    fun unauthorizedOnUnauthenticatedRequestIsNotRetried() = runTest {
        val snapshot = accountA()
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        assertEquals(ErrorKind.UNAUTHENTICATED, transport(engine).create(snapshot, "/healthz").failureKind())
        assertEquals(1, engine.requestHistory.size)
        assertTrue(tokenSource.requests.isEmpty())
    }

    @Test
    fun unauthorizedForceRefreshesOnceAndResendsSameUrlBodyAndKey() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("old", "fresh")
        val engine = statusEngine(HttpStatusCode.Unauthorized, HttpStatusCode.Created)
        assertTrue(transport(engine).create(snapshot) is ClientResult.Success)
        val (first, second) = engine.requestHistory
        assertEquals(listOf(false, true), tokenSource.requests.map { it.forceRefresh })
        assertEquals("Bearer old", first.authorization())
        assertEquals("Bearer fresh", second.authorization())
        assertEquals(first.url, second.url)
        assertEquals(first.method, second.method)
        assertEquals(first.bodyText(), second.bodyText())
        assertEquals(TEST_BODY, second.bodyText())
        assertEquals(TEST_KEY, first.headers["Idempotency-Key"])
        assertEquals(TEST_KEY, second.headers["Idempotency-Key"])
    }

    @Test
    fun secondUnauthorizedEndsUnauthenticatedWithoutThirdAttempt() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("old", "fresh")
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        assertEquals(ErrorKind.UNAUTHENTICATED, transport(engine).create(snapshot).failureKind())
        assertEquals(2, engine.requestHistory.size)
        assertEquals(2, tokenSource.requests.size)
    }

    @Test
    fun missingTokenEndsUnauthenticatedWithoutSending() = runTest {
        val snapshot = accountA()
        tokenSource.responder = { it.callback.complete(null, "auth/user-token-expired") }
        val engine = statusEngine(HttpStatusCode.OK)
        val error = transport(engine).create(snapshot).failureError()
        assertEquals(ErrorKind.UNAUTHENTICATED, error.kind)
        assertEquals("auth/user-token-expired", error.code)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun refreshFailureAfterUnauthorizedEndsUnauthenticated() = runTest {
        val snapshot = accountA()
        tokenSource.responder = {
            if (it.forceRefresh) it.callback.complete(null, "auth/refresh-failed") else it.callback.complete("old", null)
        }
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        val error = transport(engine).create(snapshot).failureError()
        assertEquals(ErrorKind.UNAUTHENTICATED, error.kind)
        assertEquals("auth/refresh-failed", error.code)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun sessionChangeDuringInitialTokenFetchSendsNothing() = runTest {
        val snapshot = accountA()
        val engine = statusEngine(HttpStatusCode.OK)
        val request = async { transport(engine).create(snapshot) }
        val pending = tokenSource.fetches.receive()
        session.changeAccount("account-b")
        pending.callback.complete("token-a", null)
        assertEquals(ErrorKind.SESSION_CHANGED, request.await().failureKind())
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun session_change_during_refresh_does_not_resend_old_body() = runTest {
        val snapshot = accountA()
        tokenSource.responder = { if (!it.forceRefresh) it.callback.complete("token-a", null) }
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        val request = async { transport(engine).create(snapshot) }
        val refresh = tokenSource.fetches.receive().let { tokenSource.fetches.receive() }
        assertTrue(refresh.forceRefresh)
        session.changeAccount("account-b")
        refresh.callback.complete("token-b", null)
        assertEquals(ErrorKind.SESSION_CHANGED, request.await().failureKind())
        assertEquals(1, engine.requestHistory.size) // only the first 401; no second POST
    }

    @Test
    fun reloginOfSameAccountDuringRefreshIsSessionChanged() = runTest {
        val snapshot = accountA()
        tokenSource.responder = { if (!it.forceRefresh) it.callback.complete("token-a", null) }
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        val request = async { transport(engine).create(snapshot) }
        tokenSource.fetches.receive()
        val refresh = tokenSource.fetches.receive()
        session.changeAccount(null)
        session.changeAccount("account-a")
        refresh.callback.complete("token-a2", null)
        assertEquals(ErrorKind.SESSION_CHANGED, request.await().failureKind())
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun sessionChangeWhileResponseIsInFlightDiscardsTheResponse() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("token-a")
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine {
            reached.complete(Unit)
            release.await()
            json(HttpStatusCode.OK, "{}")
        }
        val request = async { transport(engine).get(snapshot) }
        reached.await()
        session.changeAccount("account-b")
        release.complete(Unit)
        assertEquals(ErrorKind.SESSION_CHANGED, request.await().failureKind())
    }

    @Test
    fun staleSnapshotAtEntryNeverTouchesTokensOrNetwork() = runTest {
        val snapshot = accountA()
        session.changeAccount("account-b")
        val engine = statusEngine(HttpStatusCode.OK)
        assertEquals(ErrorKind.SESSION_CHANGED, transport(engine).get(snapshot).failureKind())
        assertTrue(engine.requestHistory.isEmpty())
        assertTrue(tokenSource.requests.isEmpty())
    }

    @Test
    fun cancellationDuringTokenFetchPropagatesAndCancelsPlatformRequest() = runTest {
        val snapshot = accountA()
        val engine = statusEngine(HttpStatusCode.OK)
        var result: ClientResult<*>? = null
        var propagated = false
        val job = launch {
            try {
                result = transport(engine).create(snapshot)
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }
        val pending = tokenSource.fetches.receive()
        job.cancel()
        job.join()
        assertTrue(propagated)
        assertNull(result)
        assertEquals(1, pending.cancelCount)
        pending.callback.complete("late", null)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun cancellationDuringRefreshPropagatesAndSendsNoSecondRequest() = runTest {
        val snapshot = accountA()
        tokenSource.responder = { if (!it.forceRefresh) it.callback.complete("token-a", null) }
        val engine = statusEngine(HttpStatusCode.Unauthorized)
        var result: ClientResult<*>? = null
        var propagated = false
        val job = launch {
            try {
                result = transport(engine).create(snapshot)
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }
        tokenSource.fetches.receive()
        val refresh = tokenSource.fetches.receive()
        job.cancel()
        job.join()
        assertTrue(propagated)
        assertNull(result)
        assertEquals(1, refresh.cancelCount)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun cancellationDuringNetworkCallPropagates() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("token-a")
        val reached = CompletableDeferred<Unit>()
        val engine = MockEngine {
            reached.complete(Unit)
            CompletableDeferred<Unit>().await()
            json(HttpStatusCode.OK, "{}")
        }
        var result: ClientResult<*>? = null
        var propagated = false
        val job = launch {
            try {
                result = transport(engine).get(snapshot)
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }
        reached.await()
        job.cancel()
        job.join()
        assertTrue(propagated)
        assertNull(result)
    }

    @Test
    fun errorEnvelopeIsMappedThroughTransport() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = MockEngine {
            json(HttpStatusCode.Conflict, """{"error":{"code":"IDEMPOTENCY_KEY_REUSED","requestId":"rid-1","details":{}}}""")
        }
        val error = transport(engine).create(snapshot).failureError()
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code)
        assertEquals("rid-1", error.requestId)
        assertNull(error.currentVersion)
    }

    @Test
    fun rateLimitCarriesRetryAfterSeconds() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = MockEngine { json(HttpStatusCode.TooManyRequests, "{}", HttpHeaders.RetryAfter to "17") }
        val error = transport(engine).get(snapshot).failureError()
        assertEquals(ErrorKind.RATE_LIMITED, error.kind)
        assertEquals(17L, error.retryAfterSeconds)
    }

    @Test
    fun serverErrorOnPostIsNotRetried() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = statusEngine(HttpStatusCode.InternalServerError, body = "oops")
        assertEquals(ErrorKind.SERVER, transport(engine).create(snapshot).failureKind())
        assertEquals(1, engine.requestHistory.size)
        assertEquals(1, tokenSource.requests.size)
    }

    @Test
    fun timeoutAndConnectionFailuresAreMappedAndNotRetried() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val cases = listOf<Pair<() -> Throwable, ErrorKind>>(
            { HttpRequestTimeoutException("https://api.example.test/v1/x", 30_000) } to ErrorKind.TIMEOUT,
            { SocketTimeoutException("socket", null) } to ErrorKind.TIMEOUT,
            { ConnectTimeoutException("connect", null) } to ErrorKind.TIMEOUT,
            { kotlinx.io.IOException("connection reset") } to ErrorKind.NETWORK,
        )
        cases.forEach { (failure, kind) ->
            var attempts = 0 // MockEngine does not record a request whose handler throws
            val engine = MockEngine { attempts++; throw failure() }
            assertEquals(kind, transport(engine).create(snapshot).failureKind())
            assertEquals(1, attempts)
        }
    }

    @Test
    fun redirectsAreNotFollowedAndAreInvalidResponse() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example.test/v1/wishlist-items"))
        }
        assertEquals(ErrorKind.INVALID_RESPONSE, transport(engine).create(snapshot).failureKind())
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun timeoutDefaultsAreAppliedToEveryRequest() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = statusEngine(HttpStatusCode.OK)
        transport(engine).get(snapshot)
        val timeout = engine.requestHistory.single().getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(30_000L, timeout?.requestTimeoutMillis)
        assertEquals(10_000L, timeout?.connectTimeoutMillis)
        assertEquals(30_000L, timeout?.socketTimeoutMillis)
    }

    @Test
    fun successResponseExposesBody() = runTest {
        val snapshot = accountA()
        tokenSource.respondToken("t")
        val engine = MockEngine { json(HttpStatusCode.OK, """{"ok":true}""") }
        val response = (transport(engine).get(snapshot) as ClientResult.Success).value
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.headers.contains(HttpHeaders.Authorization))
    }
}
