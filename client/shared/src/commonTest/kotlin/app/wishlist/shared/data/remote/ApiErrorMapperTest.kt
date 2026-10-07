package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiErrorMapperTest {
    private suspend fun map(
        status: HttpStatusCode,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): ClientError {
        val engine = MockEngine {
            respond(body, status, headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray()))
        }
        val client = HttpClient(engine) { expectSuccess = false; followRedirects = false }
        return ApiErrorMapper.fromResponse(client.get("https://api.example.test/v1/x"))
    }

    private fun envelope(code: String, requestId: String = "rid", details: String = "{}") =
        """{"error":{"code":"$code","requestId":"$requestId","details":$details}}"""

    @Test
    fun statusKinds() = runTest {
        val expected = mapOf(
            HttpStatusCode.Unauthorized to ErrorKind.UNAUTHENTICATED,
            HttpStatusCode.NotFound to ErrorKind.NOT_FOUND,
            HttpStatusCode.Conflict to ErrorKind.CONFLICT,
            HttpStatusCode.BadRequest to ErrorKind.VALIDATION,
            HttpStatusCode.UnprocessableEntity to ErrorKind.VALIDATION,
            HttpStatusCode.TooManyRequests to ErrorKind.RATE_LIMITED,
            HttpStatusCode.InternalServerError to ErrorKind.SERVER,
            HttpStatusCode.BadGateway to ErrorKind.SERVER,
            HttpStatusCode.ServiceUnavailable to ErrorKind.SERVER,
            HttpStatusCode.MovedPermanently to ErrorKind.INVALID_RESPONSE,
            HttpStatusCode.Found to ErrorKind.INVALID_RESPONSE,
            HttpStatusCode.TemporaryRedirect to ErrorKind.INVALID_RESPONSE,
            HttpStatusCode.PermanentRedirect to ErrorKind.INVALID_RESPONSE,
            HttpStatusCode.Forbidden to ErrorKind.INVALID_RESPONSE,
            HttpStatusCode.NoContent to ErrorKind.INVALID_RESPONSE,
        )
        expected.forEach { (status, kind) -> assertEquals(kind, map(status).kind, status.toString()) }
    }

    @Test
    fun envelopeCodeAndRequestId() = runTest {
        val error = map(HttpStatusCode.UnprocessableEntity, envelope("INVALID_URL", "rid-9"))
        assertEquals(ClientError(ErrorKind.VALIDATION, "INVALID_URL", "rid-9"), error)
    }

    @Test
    fun envelopeRequestIdWinsOverHeaderAndHeaderIsFallback() = runTest {
        val both = map(HttpStatusCode.NotFound, envelope("WISHLIST_ITEM_NOT_FOUND", "from-body"), mapOf("X-Request-ID" to "from-header"))
        assertEquals("from-body", both.requestId)
        val headerOnly = map(HttpStatusCode.BadGateway, "<html>bad gateway</html>", mapOf("X-Request-ID" to "from-header"))
        assertEquals("from-header", headerOnly.requestId)
        assertEquals(ErrorKind.SERVER, headerOnly.kind)
        assertNull(headerOnly.code)
    }

    @Test
    fun currentVersionFromDetailsNumberOnly() = runTest {
        val conflict = map(HttpStatusCode.Conflict, envelope("WISHLIST_ITEM_STATE_CONFLICT", details = """{"currentVersion":8}"""))
        assertEquals(8, conflict.currentVersion)
        assertEquals("WISHLIST_ITEM_STATE_CONFLICT", conflict.code)
        val asString = map(HttpStatusCode.Conflict, envelope("WISHLIST_ITEM_STATE_CONFLICT", details = """{"currentVersion":"8"}"""))
        assertNull(asString.currentVersion)
        val fractional = map(HttpStatusCode.Conflict, envelope("X", details = """{"currentVersion":8.5}"""))
        assertNull(fractional.currentVersion)
    }

    @Test
    fun idempotencyKeyReusedHasNoCurrentVersionEvenIfDetailsCarryOne() = runTest {
        val plain = map(HttpStatusCode.Conflict, envelope("IDEMPOTENCY_KEY_REUSED", "r1"))
        assertEquals(ClientError(ErrorKind.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "r1", currentVersion = null), plain)
        val defensive = map(HttpStatusCode.Conflict, envelope("IDEMPOTENCY_KEY_REUSED", details = """{"currentVersion":3}"""))
        assertNull(defensive.currentVersion)
    }

    @Test
    fun unknownCodeKeepsStatusKindAndCode() = runTest {
        val error = map(HttpStatusCode.Conflict, envelope("SOMETHING_NEW"))
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals("SOMETHING_NEW", error.code)
    }

    @Test
    fun retryAfterSecondsOnlyForRateLimit() = runTest {
        assertEquals(120L, map(HttpStatusCode.TooManyRequests, envelope("RATE_LIMITED"), mapOf("Retry-After" to "120")).retryAfterSeconds)
        assertNull(map(HttpStatusCode.TooManyRequests, "", mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")).retryAfterSeconds)
        assertNull(map(HttpStatusCode.TooManyRequests, "", mapOf("Retry-After" to "-5")).retryAfterSeconds)
        assertNull(map(HttpStatusCode.TooManyRequests).retryAfterSeconds)
        assertNull(map(HttpStatusCode.InternalServerError, "", mapOf("Retry-After" to "5")).retryAfterSeconds)
    }

    @Test
    fun malformedBodiesNeverThrow() = runTest {
        val bodies = listOf(
            "", "not json", "{", "[]", "null", "42", "\"text\"", "{}", """{"error":null}""", """{"error":"boom"}""",
            """{"error":{"code":42,"requestId":false,"details":[1]}}""",
            """{"error":{"code":"A","details":"x"}}""",
            "��",
        )
        bodies.forEach { body ->
            val error = map(HttpStatusCode.InternalServerError, body)
            assertEquals(ErrorKind.SERVER, error.kind, body)
            assertNull(error.currentVersion, body)
        }
        assertNull(map(HttpStatusCode.InternalServerError, """{"error":{"code":42}}""").code)
        assertEquals("A", map(HttpStatusCode.InternalServerError, """{"error":{"code":"A","details":"x"}}""").code)
    }

    @Test
    fun blankCodeAndRequestIdAreTreatedAsAbsent() = runTest {
        val error = map(HttpStatusCode.NotFound, """{"error":{"code":"","requestId":" "}}""", mapOf("X-Request-ID" to "h"))
        assertNull(error.code)
        assertEquals("h", error.requestId)
    }

    @Test
    fun transportFailuresMapToTimeoutOrNetwork() {
        assertEquals(ErrorKind.TIMEOUT, ApiErrorMapper.fromThrowable(io.ktor.client.plugins.HttpRequestTimeoutException("u", 1)).kind)
        assertEquals(ErrorKind.NETWORK, ApiErrorMapper.fromThrowable(kotlinx.io.IOException("reset")).kind)
        assertEquals(ErrorKind.NETWORK, ApiErrorMapper.fromThrowable(IllegalStateException("odd")).kind)
    }

    @Test
    fun headerNamesAreCaseInsensitive() = runTest {
        val error = map(HttpStatusCode.TooManyRequests, "", mapOf("retry-after" to "3", "x-request-id" to "low"))
        assertEquals(3L, error.retryAfterSeconds)
        assertEquals("low", error.requestId)
        assertEquals(HttpHeaders.RetryAfter, "Retry-After")
    }
}
