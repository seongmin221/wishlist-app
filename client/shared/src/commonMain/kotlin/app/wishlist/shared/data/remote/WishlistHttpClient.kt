package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.SessionSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

internal const val REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val CONNECT_TIMEOUT_MILLIS = 10_000L
internal const val SOCKET_TIMEOUT_MILLIS = 30_000L

/**
 * Starting defaults. Applied by [HttpTimeout]: OkHttp honours all three; Darwin's support is
 * engine specific (see docs/architecture/client/kmp.md). Redirects are never followed and
 * non-2xx statuses are returned, not thrown, so [AuthenticatedTransport] owns all decisions.
 * No Ktor Auth/Bearer plugin: its token cache would bypass the session guard.
 */
internal fun createWishlistHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

/**
 * Sends a request for one session snapshot. Stale checks (plain snapshot comparison, never the
 * session gate) run after token completion, before the first send, before the 401 resend and
 * before returning. Exactly one forced-refresh retry follows a 401; network failures and 5xx are
 * never re-sent here (OkHttp's own dead-connection recovery is separate).
 *
 * [buildRequest] runs once per attempt on a builder pre-seeded with [baseUrl], so relative URLs
 * resolve against it and a retry rebuilds the same URL/body/Idempotency-Key. The caller owns the
 * client's lifecycle.
 */
internal class AuthenticatedTransport(
    private val session: AuthSession,
    private val tokens: AuthTokenProvider,
    private val client: HttpClient,
    baseUrl: String,
) {
    private val base: Url = Url(baseUrl)

    suspend fun execute(
        snapshot: SessionSnapshot,
        apiId: ApiId,
        buildRequest: HttpRequestBuilder.() -> Unit,
    ): ClientResult<HttpResponse> {
        if (isStale(snapshot)) return sessionChanged()
        val template = buildFor(buildRequest)
        val authenticated = isAuthenticated(template)

        var token: String? = null
        if (authenticated) {
            when (val first = tokens.getToken(snapshot, forceRefresh = false)) {
                is ClientResult.Failure -> return first
                is ClientResult.Success -> token = first.value
            }
        }
        if (isStale(snapshot)) return sessionChanged()

        var response = when (val sent = send(template, token)) {
            is ClientResult.Failure -> return sent
            is ClientResult.Success -> sent.value
        }
        if (response.status.value == 401 && authenticated) {
            val refreshed = when (val second = tokens.getToken(snapshot, forceRefresh = true)) {
                is ClientResult.Failure -> return second
                is ClientResult.Success -> second.value
            }
            if (isStale(snapshot)) return sessionChanged()
            response = when (val resent = send(template, refreshed)) {
                is ClientResult.Failure -> return resent
                is ClientResult.Success -> resent.value
            }
        }
        if (isStale(snapshot)) return sessionChanged()
        return if (response.status.value in 200..299) {
            ClientResult.Success(response)
        } else {
            ClientResult.Failure(ApiErrorMapper.fromResponse(response))
        }
    }

    private fun isStale(snapshot: SessionSnapshot) = session.state.value != snapshot

    private fun sessionChanged() = ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))

    private fun buildFor(buildRequest: HttpRequestBuilder.() -> Unit) = HttpRequestBuilder().apply {
        url(base.toString())
        buildRequest()
    }

    /** Same scheme+host+port as the base URL and a path inside `/v1`. Anything else is anonymous. */
    private fun isAuthenticated(request: HttpRequestBuilder): Boolean {
        val target = request.url.build()
        val sameOrigin = target.protocol == base.protocol && target.host.equals(base.host, ignoreCase = true) &&
            target.port == base.port
        val path = target.encodedPath
        return sameOrigin && (path == "/v1" || path.startsWith("/v1/"))
    }

    private suspend fun send(
        template: HttpRequestBuilder,
        token: String?,
    ): ClientResult<HttpResponse> = try {
        ClientResult.Success(client.request(HttpRequestBuilder().takeFrom(template).apply {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A cancelled caller can surface as an IO error; it must propagate, not become NETWORK.
        currentCoroutineContext().ensureActive()
        ClientResult.Failure(ApiErrorMapper.fromThrowable(e))
    }
}
