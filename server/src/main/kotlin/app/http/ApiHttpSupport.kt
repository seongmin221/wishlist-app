package app.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val ApiJson = Json { encodeDefaults = true }

private val RequestIdKey = AttributeKey<String>("api-request-id")
private val ApiRequestId = createApplicationPlugin("ApiRequestId") {
    onCall { call -> call.ensureApiRequestId() }
}

private fun ApplicationCall.ensureApiRequestId(): String =
    attributes.getOrNull(RequestIdKey) ?: UUID.randomUUID().toString().also { id ->
        attributes.put(RequestIdKey, id)
        response.headers.append("X-Request-ID", id)
    }

fun Application.installApiHttpSupport() {
    install(ApiRequestId)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val clientError = when (cause) {
                is BadRequestException -> HttpStatusCode.BadRequest to "BAD_REQUEST"
                is NotFoundException -> HttpStatusCode.NotFound to "NOT_FOUND"
                is UnsupportedMediaTypeException -> HttpStatusCode.UnsupportedMediaType to "UNSUPPORTED_MEDIA_TYPE"
                is PayloadTooLargeException -> HttpStatusCode.PayloadTooLarge to "PAYLOAD_TOO_LARGE"
                else -> null
            }
            if (clientError != null) {
                call.respondApiError(clientError.first, clientError.second)
            } else {
                // Exception messages may contain credentials, SQL or user input. Keep type and frames only.
                call.application.log.error("Unhandled API error requestId={} exceptionType={} stackTrace={}",
                    call.ensureApiRequestId(), cause.javaClass.name, cause.stackTrace.joinToString("\n"))
                call.respondApiError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
            }
        }
    }
}

suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    details: Map<String, JsonElement> = emptyMap(),
) {
    val envelope = ApiErrorEnvelope(ApiError(code, ensureApiRequestId(), details))
    respondText(ApiJson.encodeToString(envelope), ContentType.Application.Json, status)
}
