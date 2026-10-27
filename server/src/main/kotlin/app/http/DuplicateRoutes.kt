package app.http

import app.duplicates.DuplicateDecisionRequest
import app.duplicates.DuplicateDecisionResult
import app.duplicates.DuplicateDecisionService
import app.duplicates.DuplicateException
import app.duplicates.DuplicatePage
import app.duplicates.DuplicateReadService
import app.common.parseCanonicalUuid
import app.media.MediaUrlSigner
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.request.receiveText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.sql.DataSource

/** Blocking B9 use cases; the routes call them on the IO dispatcher. */
data class DuplicateHandlers(
    val list: (UUID, UUID, DuplicateQuery) -> DuplicatePage,
    val decide: (UUID, UUID, UUID, DuplicateDecisionRequest) -> DuplicateDecisionResult,
) {
    companion object {
        fun of(source: DataSource): DuplicateHandlers {
            val reads = DuplicateReadService(source)
            val decisions = DuplicateDecisionService(source)
            return DuplicateHandlers(list = { owner, item, q -> reads.list(owner, item, q.limit, q.after) }, decide = decisions::decide)
        }
    }
}

fun Route.duplicateRoutes(handlers: DuplicateHandlers, signer: MediaUrlSigner, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    /** Owner and item ID in the order 401 then 400; null means an error was already sent. */
    suspend fun ApplicationCall.target(): Pair<UUID, UUID>? {
        val owner = ownerResolver(this) ?: return null.also { respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED") }
        val item = parseCanonicalUuid(parameters["id"]) ?: return null.also { respondApiError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID") }
        return owner to item
    }

    get("/v1/wishlist-items/{id}/duplicate-candidates") {
        val (owner, item) = call.target() ?: return@get
        val query = when (val parsed = DuplicateRequestParser.query(owner, item, call.request.queryParameters)) {
            DuplicateQueryParseResult.InvalidQuery -> return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_QUERY")
            DuplicateQueryParseResult.InvalidCursor -> return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_CURSOR")
            is DuplicateQueryParseResult.Valid -> parsed.query
        }
        duplicateErrors(call) {
            val (page, urls) = withContext(Dispatchers.IO) {
                val page = handlers.list(owner, item, query)
                page to signer.signAll((listOf(page.item) + page.candidates.map { it.existingItem }).mediaKeys())
            }
            val dto = DuplicatePageDto(
                WishlistItemViewMapper.map(page.item, urls),
                page.candidates.map { candidate ->
                    DuplicateCandidateDto(candidate.id.toString(), candidate.kind.name, candidate.bases.map { it.name }, candidate.version,
                        candidate.allowedDecisions.map { it.name }, WishlistItemViewMapper.map(candidate.existingItem, urls))
                },
                page.totalCount, page.next?.let { CandidateCursorCodec.encodeDuplicates(owner, item, it) },
            )
            call.respondText(ApiJson.encodeToString(dto), ContentType.Application.Json)
        }
    }
    put("/v1/wishlist-items/{id}/duplicate-decisions") {
        val (owner, item) = call.target() ?: return@put
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@put call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val request = when (val parsed = DuplicateRequestParser.decision(call.receiveText())) {
            is DuplicateDecisionParseResult.Valid -> parsed.request
            is DuplicateDecisionParseResult.Invalid -> return@put call.respondApiError(HttpStatusCode.UnprocessableEntity,
                "INVALID_DUPLICATE_DECISION_INPUT", candidateFieldDetails(parsed.fields))
        }
        duplicateErrors(call) {
            val result = withContext(Dispatchers.IO) { handlers.decide(owner, item, key, request) }
            if (result.replayed) call.response.headers.append("Idempotency-Replayed", "true")
            val dto = DuplicateDecisionDto(
                DecidedCandidateDto(result.candidateId.toString(), result.decision.name, result.decidedAt.toString(), result.candidateVersion),
                result.deletedItemId?.toString(), result.remainingItemIds.map { it.toString() },
                result.purposes.map { MovedPurposeDto(it.id.toString(), it.candidateCount, it.membershipVersion) },
            )
            call.respondText(ApiJson.encodeToString(dto), ContentType.Application.Json)
        }
    }
}

internal suspend fun duplicateErrors(call: ApplicationCall, block: suspend () -> Unit) {
    try { block() } catch (error: DuplicateException) {
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
