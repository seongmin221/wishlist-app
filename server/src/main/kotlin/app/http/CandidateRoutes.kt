package app.http

import app.candidates.CandidateException
import app.candidates.CandidateMoveRequest
import app.candidates.CandidateMoveResult
import app.candidates.CandidateMoveService
import app.candidates.CandidatePage
import app.candidates.CandidateReadService
import app.common.parseCanonicalUuid
import app.media.MediaUrlSigner
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.sql.DataSource

/** Blocking B8 use cases; the routes call them on the IO dispatcher. */
data class CandidateHandlers(
    val list: (UUID, UUID, CandidateQuery) -> CandidatePage,
    val move: (UUID, UUID, UUID, CandidateMoveRequest) -> CandidateMoveResult,
) {
    companion object {
        fun of(source: DataSource): CandidateHandlers {
            val reads = CandidateReadService(source)
            val moves = CandidateMoveService(source)
            return CandidateHandlers(
                list = { owner, purpose, q -> reads.list(owner, purpose, q.filter, q.limit, q.after, q.facets) },
                move = moves::move,
            )
        }
    }
}

fun Route.candidateRoutes(handlers: CandidateHandlers, signer: MediaUrlSigner, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    /** Owner and purpose ID in the order 401 then 400; null means an error was already sent. */
    suspend fun ApplicationCall.target(): Pair<UUID, UUID>? {
        val owner = ownerResolver(this) ?: return null.also { respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED") }
        val purpose = parseCanonicalUuid(parameters["id"]) ?: return null.also { respondApiError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID") }
        return owner to purpose
    }

    get("/v1/purposes/{id}/candidate-items") {
        val (owner, purpose) = call.target() ?: return@get
        val query = when (val parsed = CandidateRequestParser.query(owner, purpose, call.request.queryParameters)) {
            CandidateQueryParseResult.InvalidQuery -> return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_QUERY")
            CandidateQueryParseResult.InvalidCursor -> return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_CURSOR")
            is CandidateQueryParseResult.Valid -> parsed.query
        }
        candidateErrors(call) {
            val (page, urls) = withContext(Dispatchers.IO) {
                val page = handlers.list(owner, purpose, query)
                page to signer.signAll(page.items.mediaKeys())
            }
            val dto = CandidatePageDto(page.items.map { WishlistItemViewMapper.map(it, urls) }, page.totalCount,
                page.next?.let { CandidateCursorCodec.encode(owner, purpose, query.filter, it) },
                page.facets?.categories?.map { CategoryFacetDto(it.categoryId, it.parentId, it.count) }, page.facets?.unassignedCount)
            call.respondText(ApiJson.encodeToString(dto), ContentType.Application.Json)
        }
    }
    post("/v1/purposes/{id}/candidate-moves") {
        val (owner, purpose) = call.target() ?: return@post
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val request = when (val parsed = CandidateRequestParser.move(call.receiveText())) {
            is CandidateMoveParseResult.Valid -> parsed.request
            is CandidateMoveParseResult.Invalid -> return@post call.respondApiError(HttpStatusCode.UnprocessableEntity,
                "INVALID_CANDIDATE_INPUT", candidateFieldDetails(parsed.fields))
        }
        candidateErrors(call) {
            val result = withContext(Dispatchers.IO) { handlers.move(owner, purpose, key, request) }
            if (result.replayed) call.response.headers.append("Idempotency-Replayed", "true")
            val dto = CandidateMoveDto(result.items.map { MovedItemDto(it.id.toString(), it.version, it.purposeId.toString()) },
                result.purposes.map { MovedPurposeDto(it.id.toString(), it.candidateCount, it.membershipVersion) })
            call.respondText(ApiJson.encodeToString(dto), ContentType.Application.Json)
        }
    }
}

internal fun candidateFieldDetails(fields: Set<String>): Map<String, JsonElement> =
    if (fields.isEmpty()) emptyMap() else mapOf("fields" to JsonArray(fields.sorted().map(::JsonPrimitive)))

internal suspend fun candidateErrors(call: ApplicationCall, block: suspend () -> Unit) {
    try { block() } catch (error: CandidateException) {
        val status = when {
            error.code.endsWith("_NOT_FOUND") -> HttpStatusCode.NotFound
            error.code.startsWith("INVALID_") -> HttpStatusCode.UnprocessableEntity
            else -> HttpStatusCode.Conflict
        }
        val details = candidateFieldDetails(error.fields).toMutableMap()
        error.itemId?.let { details["itemId"] = JsonPrimitive(it.toString()) }
        error.currentVersion?.let { details["currentVersion"] = JsonPrimitive(it) }
        call.respondApiError(status, error.code, details)
    }
}
