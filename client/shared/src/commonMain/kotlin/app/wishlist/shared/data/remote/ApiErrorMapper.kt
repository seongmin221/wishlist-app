package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps HTTP failures to [ClientError]. Server envelope: `{"error":{"code","requestId","details"}}`
 * plus the `X-Request-ID` header. The body is untrusted: any malformed shape degrades to the
 * HTTP-status kind with a null code and never throws (cancellation excepted).
 */
internal object ApiErrorMapper {
    private const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"

    suspend fun fromResponse(response: HttpResponse): ClientError {
        val status = response.status.value
        val envelope = readEnvelope(response)
        val code = envelope?.get("code").stringOrNull()
        val requestId = envelope?.get("requestId").stringOrNull()
            ?: response.headers["X-Request-ID"]?.takeIf { it.isNotBlank() }
        return ClientError(
            kind = kindOf(status),
            code = code,
            requestId = requestId,
            currentVersion = currentVersion(code, envelope),
            retryAfterSeconds = if (status == 429) retryAfterSeconds(response) else null,
        )
    }

    fun fromThrowable(cause: Throwable): ClientError {
        if (cause is CancellationException) throw cause
        val kind = when (cause) {
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> ErrorKind.TIMEOUT
            else -> ErrorKind.NETWORK
        }
        return ClientError(kind)
    }

    private fun kindOf(status: Int): ErrorKind = when (status) {
        401 -> ErrorKind.UNAUTHENTICATED
        404 -> ErrorKind.NOT_FOUND
        409 -> ErrorKind.CONFLICT
        400, 422 -> ErrorKind.VALIDATION
        429 -> ErrorKind.RATE_LIMITED
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.INVALID_RESPONSE // any 3xx, unexpected 4xx such as 403, or a success status here
    }

    private suspend fun readEnvelope(response: HttpResponse): JsonObject? = try {
        val root = Json.parseToJsonElement(response.bodyAsText())
        (root as? JsonObject)?.get("error") as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * `error.details.currentVersion` (JSON number, nullable) is fixed by the item state API
     * contract. CONTRACT-PENDING(ITEM-04/ITEM-07/ITEM-08): which follow-up mutation error codes
     * carry it is not fixed yet, so it is read generically for every code except the B1
     * IDEMPOTENCY_KEY_REUSED, which never has one.
     */
    private fun currentVersion(code: String?, envelope: JsonObject?): Int? {
        if (code == IDEMPOTENCY_KEY_REUSED) return null
        val value = (envelope?.get("details") as? JsonObject)?.get("currentVersion") as? JsonPrimitive ?: return null
        return if (value.isString) null else value.intOrNull
    }

    private fun retryAfterSeconds(response: HttpResponse): Long? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
