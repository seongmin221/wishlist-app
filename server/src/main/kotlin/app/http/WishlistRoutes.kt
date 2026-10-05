package app.http

import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import app.wishlist.WishlistItem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlinx.coroutines.CancellationException

fun Route.wishlistRoutes(
    service: CreateWishlistItemService,
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) {
    post("/v1/wishlist-items") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val key = call.request.headers["Idempotency-Key"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val sourceUrl = try {
            Json.parseToJsonElement(call.receiveText()).jsonObject["sourceUrl"]?.jsonPrimitive?.content
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            null
        } ?: return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_URL")

        when (val result = service.create(owner, key, sourceUrl)) {
            is CreateResult.Created -> {
                call.response.headers.append(HttpHeaders.Location, "/v1/wishlist-items/${result.itemId}")
                call.respondText(itemJson(result.item), ContentType.Application.Json, HttpStatusCode.Created)
            }
            is CreateResult.Replayed -> {
                call.response.headers.append("Idempotency-Replayed", "true")
                call.respondText(itemJson(result.item), ContentType.Application.Json, HttpStatusCode.OK)
            }
            is CreateResult.IdempotencyKeyReused -> call.respondApiError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
            CreateResult.InvalidUrl -> call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_URL")
        }
    }
}

private fun itemJson(item: WishlistItem) =
    """{"id":"${item.id}","clientSubmissionId":"${item.clientSubmissionId}","version":${item.version},"sourceUrl":${JsonPrimitive(item.sourceUrl)},"product":{"name":null,"imageUrl":null,"price":null,"currency":null},"category":{"id":null,"source":null,"missingReason":null},"purpose":{"id":null,"source":"UNASSIGNED"},"analysis":{"status":"${item.analysisStatus}","failureCode":null},"reviewStatus":"NOT_REQUIRED","lifecycleStatus":"${item.lifecycleStatus}","requiredAction":"ANALYSIS_IN_PROGRESS","manualCompletionAt":null,"createdAt":"${item.createdAt}","updatedAt":"${item.updatedAt}"}"""
