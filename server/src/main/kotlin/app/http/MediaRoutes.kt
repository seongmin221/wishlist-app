package app.http

import app.common.parseCanonicalUuid
import app.media.MediaException
import app.media.MediaService
import app.media.MediaUrlSigner
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import org.slf4j.LoggerFactory

/** Media use cases and the display signer shared with item, purpose and home responses. */
data class MediaWiring(val service: MediaService, val signer: MediaUrlSigner)

fun Route.mediaRoutes(wiring: MediaWiring, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    post("/v1/media/upload-intents") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val request = when (val parsed = parseMediaIntentRequest(call.receiveText())) {
            is MediaRequestParseResult.Valid -> parsed.request
            is MediaRequestParseResult.Invalid -> return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, parsed.code,
                if (parsed.fields.isEmpty()) emptyMap() else mapOf("fields" to JsonArray(parsed.fields.sorted().map(::JsonPrimitive))))
        }
        mediaErrors(call) {
            val result = withContext(Dispatchers.IO) { wiring.service.createIntent(owner, key, request) }
            if (result.replayed) call.response.headers.append("Idempotency-Replayed", "true")
            call.respondText(ApiJson.encodeToString(result.view.toDto()), ContentType.Application.Json,
                if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created)
        }
    }
    post("/v1/media/{id}/completion") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val id = parseCanonicalUuid(call.parameters["id"]) ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_MEDIA_ID")
        mediaErrors(call) {
            val view = withContext(Dispatchers.IO) { wiring.service.complete(owner, id) }
            call.respondText(ApiJson.encodeToString(view.toDto()), ContentType.Application.Json)
        }
    }
}

/** Status for every code [MediaException] can carry; a new code must be added here. */
internal val MEDIA_ERROR_STATUS: Map<String, HttpStatusCode> = mapOf(
    "MEDIA_NOT_FOUND" to HttpStatusCode.NotFound,
    "IDEMPOTENCY_KEY_REUSED" to HttpStatusCode.Conflict,
    "MEDIA_OBJECT_MISSING" to HttpStatusCode.Conflict,
    "MEDIA_UPLOAD_EXPIRED" to HttpStatusCode.Gone,
    "INVALID_MEDIA_REQUEST" to HttpStatusCode.UnprocessableEntity,
    "UNSUPPORTED_MEDIA_TYPE" to HttpStatusCode.UnprocessableEntity,
    "MEDIA_TOO_LARGE" to HttpStatusCode.UnprocessableEntity,
    "MEDIA_DIMENSIONS_EXCEEDED" to HttpStatusCode.UnprocessableEntity,
    "MEDIA_INVALID_IMAGE" to HttpStatusCode.UnprocessableEntity,
    "MEDIA_UPLOAD_LIMIT_REACHED" to HttpStatusCode.TooManyRequests,
    "MEDIA_STORAGE_UNAVAILABLE" to HttpStatusCode.ServiceUnavailable,
    "MEDIA_BUSY" to HttpStatusCode.ServiceUnavailable,
)

private suspend fun mediaErrors(call: ApplicationCall, block: suspend () -> Unit) {
    try { block() } catch (error: MediaException) {
        val status = MEDIA_ERROR_STATUS[error.code] ?: run {
            // An unmapped code is a server bug; do not let it pass as a client conflict.
            LoggerFactory.getLogger("app.http.MediaRoutes").error("Unmapped media error code={}", error.code)
            HttpStatusCode.InternalServerError
        }
        error.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        call.respondApiError(status, error.code, error.reason?.let { mapOf("reason" to JsonPrimitive(it.name)) } ?: emptyMap())
    }
}
