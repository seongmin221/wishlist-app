package app.http

import app.common.parseCanonicalUuid
import app.purpose.*
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

fun Route.purposeRoutes(service: PurposeService, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/purposes") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val query = call.request.queryParameters
        if (query.names().any { it !in setOf("projection", "limit", "cursor") } || query.names().any { query.getAll(it)!!.size != 1 })
            return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_QUERY")
        val projection = PurposeProjection.entries.firstOrNull { it.name == query["projection"] }
            ?: return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_QUERY")
        val limit = query["limit"]?.let { value -> value.toIntOrNull()?.takeIf { it in 1..PurposeLimits.PAGE_LIMIT && value.matches(Regex("[1-9][0-9]*")) } }
            ?: if (query["limit"] == null) PurposeLimits.PAGE_LIMIT else return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_QUERY")
        val after = query["cursor"]?.let { PurposeCursorCodec.decode(owner, projection, it)
            ?: return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_CURSOR") }
        val page = withContext(Dispatchers.IO) { service.list(owner, projection, limit, after) }
        val next = page.next?.let { PurposeCursorCodec.encode(owner, projection, it) }
        val payload = when (projection) {
            PurposeProjection.SELECT -> ApiJson.encodeToString(PurposeSelectListDto(projection.name, page.entries.map { (p, _) ->
                PurposeSelectItemDto(p.id.toString(), p.input.name, p.input.color.name, p.input.icon.name, p.version) },
                next, page.activeCount, PurposeLimits.ACTIVE_LIMIT, page.archiveSummary()))
            PurposeProjection.SUMMARY -> ApiJson.encodeToString(PurposeSummaryListDto(projection.name, page.entries.map { (p, previews) ->
                PurposeSummaryItemDto(p.id.toString(), p.input.name, p.input.color.name, p.input.icon.name, p.version, p.input.description,
                    p.candidateCount, p.activityDto(), previews.map { PurposePreviewDto(it.itemId.toString(), it.imageUrl) }) },
                next, page.activeCount, PurposeLimits.ACTIVE_LIMIT, page.archiveSummary()))
        }
        call.respondText(payload, ContentType.Application.Json)
    }
    post("/v1/purposes") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val input = when (val parsed = parsePurposeCreateRequest(call.receiveText())) {
            is PurposeParseResult.Valid -> parsed.request
            is PurposeParseResult.Invalid -> return@post call.respondPurposeInputError(parsed.fields)
        }
        purposeErrors(call) {
            val result = withContext(Dispatchers.IO) { service.create(owner, key, input) }
            if (result.replayed) call.response.headers.append("Idempotency-Replayed", "true")
            else call.response.headers.append(HttpHeaders.Location, "/v1/purposes/${result.purpose.id}")
            call.respondText(ApiJson.encodeToString(PurposeCreationDto(result.purpose.toDto(), result.activeCount)), ContentType.Application.Json,
                if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created)
        }
    }
    get("/v1/purposes/{id}") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val id = parseCanonicalUuid(call.parameters["id"]) ?: return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID")
        val purpose = withContext(Dispatchers.IO) { service.get(owner, id) }
            ?: return@get call.respondApiError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
        call.respondText(ApiJson.encodeToString(purpose.toDto()), ContentType.Application.Json)
    }
    patch("/v1/purposes/{id}") {
        val owner = ownerResolver(call) ?: return@patch call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val id = parseCanonicalUuid(call.parameters["id"]) ?: return@patch call.respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID")
        val request = when (val parsed = parsePurposePatchRequest(call.receiveText())) {
            is PurposeParseResult.Valid -> parsed.request
            is PurposeParseResult.Invalid -> return@patch call.respondPurposeInputError(parsed.fields)
        }
        purposeErrors(call) {
            val purpose = withContext(Dispatchers.IO) { service.patch(owner, id, request.expectedVersion, request.changes) }
            call.respondText(ApiJson.encodeToString(purpose.toDto()), ContentType.Application.Json)
        }
    }
}

private fun fieldDetails(fields: Set<String>): Map<String, JsonElement> =
    if (fields.isEmpty()) emptyMap() else mapOf("fields" to JsonArray(fields.sorted().map(::JsonPrimitive)))

private suspend fun ApplicationCall.respondPurposeInputError(fields: Set<String>) =
    respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_PURPOSE_INPUT", fieldDetails(fields))

private suspend fun purposeErrors(call: ApplicationCall, block: suspend () -> Unit) {
    try { block() } catch (error: PurposeException) {
        val status = when (error.code) {
            "PURPOSE_NOT_FOUND" -> HttpStatusCode.NotFound
            "PURPOSE_CREATE_RATE_LIMITED" -> HttpStatusCode.TooManyRequests
            "INVALID_PURPOSE_INPUT" -> HttpStatusCode.UnprocessableEntity
            else -> HttpStatusCode.Conflict
        }
        val details = fieldDetails(error.fields).toMutableMap()
        error.currentVersion?.let { details["currentVersion"] = JsonPrimitive(it) }
        error.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        call.respondApiError(status, error.code, details)
    }
}
