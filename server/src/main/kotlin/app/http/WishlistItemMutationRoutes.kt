package app.http

import app.common.parseCanonicalUuid
import app.media.MediaUrlSigner
import app.wishlist.DeleteResult
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationException
import app.wishlist.ItemMutationService
import app.wishlist.ReanalysisResult
import app.wishlist.ReanalysisService
import app.wishlist.ReviewDecision
import app.wishlist.WishlistItem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Blocking B7 use cases; the routes call them on the IO dispatcher. */
data class ItemMutationHandlers(
    val edit: (UUID, UUID, Int, ItemChanges) -> WishlistItem,
    val completeManually: (UUID, UUID, Int, ItemChanges) -> WishlistItem,
    val decideReview: (UUID, UUID, Int, ReviewDecision, ItemChanges) -> WishlistItem,
    val delete: (UUID, UUID) -> DeleteResult,
    val reanalyze: (UUID, UUID, UUID) -> ReanalysisResult,
) {
    companion object {
        fun of(mutations: ItemMutationService, reanalysis: ReanalysisService) = ItemMutationHandlers(
            mutations::edit, mutations::completeManually, mutations::decideReview, mutations::delete, reanalysis::reanalyze,
        )
    }
}

fun Route.wishlistItemMutationRoutes(handlers: ItemMutationHandlers, signer: MediaUrlSigner, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    suspend fun ApplicationCall.respondItem(item: WishlistItem) {
        val urls = withContext(Dispatchers.IO) { signer.signAll(listOf(item).mediaKeys()) }
        respondText(ApiJson.encodeToString(WishlistItemViewMapper.map(item, urls)), ContentType.Application.Json)
    }

    /** Owner and item ID in the order 401 then 400; null means an error was already sent. */
    suspend fun ApplicationCall.target(): Pair<UUID, UUID>? {
        val owner = ownerResolver(this) ?: return null.also { respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED") }
        val item = parseCanonicalUuid(parameters["id"]) ?: return null.also { respondApiError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID") }
        return owner to item
    }

    patch("/v1/wishlist-items/{id}") {
        val (owner, id) = call.target() ?: return@patch
        val request = when (val parsed = parseItemPatchRequest(call.receiveText())) {
            is ItemParseResult.Valid -> parsed.request
            is ItemParseResult.Invalid -> return@patch call.respondItemInputError(parsed.fields)
        }
        itemErrors(call) {
            call.respondItem(withContext(Dispatchers.IO) { handlers.edit(owner, id, request.expectedVersion, request.changes) })
        }
    }
    put("/v1/wishlist-items/{id}/manual-completion") {
        val (owner, id) = call.target() ?: return@put
        val request = when (val parsed = parseManualCompletionRequest(call.receiveText())) {
            is ItemParseResult.Valid -> parsed.request
            is ItemParseResult.Invalid -> return@put call.respondItemInputError(parsed.fields)
        }
        itemErrors(call) {
            call.respondItem(withContext(Dispatchers.IO) { handlers.completeManually(owner, id, request.expectedVersion, request.changes) })
        }
    }
    put("/v1/wishlist-items/{id}/review-decision") {
        val (owner, id) = call.target() ?: return@put
        val request = when (val parsed = parseReviewDecisionRequest(call.receiveText())) {
            is ItemParseResult.Valid -> parsed.request
            is ItemParseResult.Invalid -> return@put call.respondItemInputError(parsed.fields)
        }
        itemErrors(call) {
            call.respondItem(withContext(Dispatchers.IO) {
                handlers.decideReview(owner, id, request.expectedVersion, request.decision, request.changes)
            })
        }
    }
    delete("/v1/wishlist-items/{id}") {
        val (owner, id) = call.target() ?: return@delete
        itemErrors(call) {
            withContext(Dispatchers.IO) { handlers.delete(owner, id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/v1/wishlist-items/{id}/analysis-attempts") {
        val (owner, id) = call.target() ?: return@post
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        if (!isValidReanalysisBody(call.receiveText())) return@post call.respondItemInputError(emptySet())
        itemErrors(call) {
            when (val result = withContext(Dispatchers.IO) { handlers.reanalyze(owner, id, key) }) {
                is ReanalysisResult.Started -> call.respondItem(result.item)
                is ReanalysisResult.Replayed -> {
                    call.response.headers.append("Idempotency-Replayed", "true")
                    call.respondItem(result.item)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondItemInputError(fields: Set<String>) =
    respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT", itemFieldDetails(fields))

private fun itemFieldDetails(fields: Set<String>): Map<String, JsonElement> =
    if (fields.isEmpty()) emptyMap() else mapOf("fields" to JsonArray(fields.sorted().map(::JsonPrimitive)))

private suspend fun itemErrors(call: ApplicationCall, block: suspend () -> Unit) {
    try { block() } catch (error: ItemMutationException) {
        val status = when (error.code) {
            "WISHLIST_ITEM_NOT_FOUND" -> HttpStatusCode.NotFound
            "INVALID_WISHLIST_ITEM_INPUT" -> HttpStatusCode.UnprocessableEntity
            "WISHLIST_ITEM_REANALYSIS_RATE_LIMITED" -> HttpStatusCode.TooManyRequests
            else -> HttpStatusCode.Conflict
        }
        val details = itemFieldDetails(error.fields).toMutableMap()
        error.currentVersion?.let { details["currentVersion"] = JsonPrimitive(it) }
        error.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        call.respondApiError(status, error.code, details)
    }
}
