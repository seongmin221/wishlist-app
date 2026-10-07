package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.PlatformTokenSource
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.TokenCallback
import app.wishlist.shared.core.TokenRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel

internal const val TEST_BASE_URL = "https://api.example.test"
internal const val TEST_KEY = "6f1c8b0e-1d52-4a4f-9a58-0b7b1f6a2c11"
internal const val TEST_BODY = """{"sourceUrl":"https://shop.example/p/1","clientCreatedAt":"2026-10-07T00:00:00Z"}"""

/** Records every platform token request; completion is driven by the test, like a Swift SDK would. */
internal class FakeTokenSource : PlatformTokenSource {
    class Req(val forceRefresh: Boolean, val callback: TokenCallback) : TokenRequest {
        var cancelCount = 0
        override fun cancel() { cancelCount++ }
    }

    val requests = mutableListOf<Req>()
    val fetches = Channel<Req>(Channel.UNLIMITED)

    /** Optional synchronous responder; runs inside fetchToken before it returns. */
    var responder: ((Req) -> Unit)? = null

    override fun fetchToken(forceRefresh: Boolean, completion: TokenCallback): TokenRequest {
        val request = Req(forceRefresh, completion)
        requests += request
        fetches.trySend(request)
        responder?.invoke(request)
        return request
    }

    fun respondToken(first: String?, refreshed: String? = first) {
        responder = { it.callback.complete(if (it.forceRefresh) refreshed else first, null) }
    }
}

/** Test-only ITEM-01 request builder. The real repository is wired in Task 6b. */
internal suspend fun AuthenticatedTransport.create(
    snapshot: SessionSnapshot,
    target: String = "/v1/wishlist-items",
): ClientResult<HttpResponse> = execute(snapshot, ApiId.ITEM_01) {
    method = HttpMethod.Post
    url(target)
    header("Idempotency-Key", TEST_KEY)
    header(HttpHeaders.ContentType, "application/json")
    setBody(TextContent(TEST_BODY, io.ktor.http.ContentType.Application.Json))
}

internal suspend fun AuthenticatedTransport.get(
    snapshot: SessionSnapshot,
    target: String = "/v1/wishlist-items/6f1c8b0e-1d52-4a4f-9a58-0b7b1f6a2c11",
): ClientResult<HttpResponse> = execute(snapshot, ApiId.ITEM_03) {
    method = HttpMethod.Get
    url(target)
}

internal fun ClientResult<*>.failureError(): ClientError = (this as ClientResult.Failure).error
internal fun ClientResult<*>.failureKind() = failureError().kind

internal fun HttpRequestData.bodyText(): String = (body as TextContent).text
internal fun HttpRequestData.authorization(): String? = headers[HttpHeaders.Authorization]

internal fun MockRequestHandleScope.json(status: HttpStatusCode, body: String, vararg extra: Pair<String, String>) =
    respond(
        body, status,
        headersOf(HttpHeaders.ContentType to listOf("application/json"), *extra.map { it.first to listOf(it.second) }.toTypedArray()),
    )

internal fun transportOf(
    session: app.wishlist.shared.core.AuthSession,
    tokens: AuthTokenProvider,
    engine: MockEngine,
) = AuthenticatedTransport(session, tokens, createWishlistHttpClient(engine), TEST_BASE_URL)
