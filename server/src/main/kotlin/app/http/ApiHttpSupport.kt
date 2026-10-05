package app.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
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
            call.respondApiError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
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
