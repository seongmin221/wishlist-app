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

fun Route.wishlistRoutes(
    service: CreateWishlistItemService,
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) {
    post("/v1/wishlist-items") {
        val owner = ownerResolver(call) ?: return@post call.respondText(
            """{"error":{"code":"UNAUTHORIZED"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized,
        )
        val key = call.request.headers["Idempotency-Key"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondText(
                """{"error":{"code":"INVALID_IDEMPOTENCY_KEY"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
        val sourceUrl = runCatching {
            Json.parseToJsonElement(call.receiveText()).jsonObject["sourceUrl"]?.jsonPrimitive?.content
        }.getOrNull() ?: return@post call.respondText(
            """{"error":{"code":"INVALID_URL"}}""", ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
        )

        when (val result = service.create(owner, key, sourceUrl)) {
            is CreateResult.Created -> {
                call.response.headers.append(HttpHeaders.Location, "/v1/wishlist-items/${result.itemId}")
                call.respondText(itemJson(result.item), ContentType.Application.Json, HttpStatusCode.Created)
            }
            is CreateResult.Replayed -> {
                call.response.headers.append("Idempotency-Replayed", "true")
                call.respondText(itemJson(result.item), ContentType.Application.Json, HttpStatusCode.OK)
            }
            is CreateResult.IdempotencyKeyReused -> call.respondText(
                """{"error":{"code":"IDEMPOTENCY_KEY_REUSED"}}""", ContentType.Application.Json, HttpStatusCode.Conflict,
            )
            CreateResult.InvalidUrl -> call.respondText(
                """{"error":{"code":"INVALID_URL"}}""", ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
            )
        }
    }
}

private fun itemJson(item: WishlistItem) =
    """{"id":"${item.id}","clientSubmissionId":"${item.clientSubmissionId}","version":${item.version},"sourceUrl":${JsonPrimitive(item.sourceUrl)},"product":{"name":null,"imageUrl":null,"price":null,"currency":null},"category":{"id":null,"source":null,"missingReason":null},"purpose":{"id":null,"source":"UNASSIGNED"},"analysis":{"status":"${item.analysisStatus}","failureCode":null},"reviewStatus":"NOT_REQUIRED","lifecycleStatus":"${item.lifecycleStatus}","requiredAction":"ANALYSIS_IN_PROGRESS","manualCompletionAt":null,"createdAt":"${item.createdAt}","updatedAt":"${item.updatedAt}"}"""
