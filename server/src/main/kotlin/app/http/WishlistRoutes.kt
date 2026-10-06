package app.http

import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import app.wishlist.GetWishlistItemService
import app.wishlist.WishlistItem
import app.wishlist.WishlistItemViewMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.DateTimeException
import java.util.UUID

fun Route.wishlistRoutes(
    service: CreateWishlistItemService,
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) = wishlistRoutes(service::create, GetWishlistItemService(service.items)::get, ownerResolver)

fun Route.wishlistRoutes(
    create: (UUID, UUID, String, Instant?) -> CreateResult,
    getItem: (UUID, UUID) -> WishlistItem? = { _, _ -> null },
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) {
    get("/v1/wishlist-items/{id}") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val rawId = call.parameters["id"]
        val itemId = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.toString().equals(rawId, ignoreCase = true) }
            ?: return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
        val item = withContext(Dispatchers.IO) { getItem(owner, itemId) }
            ?: return@get call.respondApiError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
        call.respondText(ApiJson.encodeToString(WishlistItemViewMapper.map(item)), ContentType.Application.Json)
    }

    post("/v1/wishlist-items") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val key = call.request.headers["Idempotency-Key"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val body = try {
            Json.parseToJsonElement(call.receiveText()) as? JsonObject
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            null
        }
        val sourceUrl = (body?.get("sourceUrl") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_URL")
        val timeValue = body["clientCreatedAt"]
        val clientCreatedAt = if (timeValue == null || timeValue == JsonNull) null else {
            val rawTime = (timeValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_CLIENT_CREATED_AT")
            try {
                OffsetDateTime.parse(rawTime).also { require(it.year in 1..9999) }.toInstant()
            } catch (_: DateTimeException) {
                return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_CLIENT_CREATED_AT")
            } catch (_: IllegalArgumentException) {
                return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_CLIENT_CREATED_AT")
            }
        }
        when (val result = withContext(Dispatchers.IO) { create(owner, key, sourceUrl, clientCreatedAt) }) {
            is CreateResult.Created -> {
                call.response.headers.append(HttpHeaders.Location, "/v1/wishlist-items/${result.itemId}")
                call.respondText(ApiJson.encodeToString(WishlistItemViewMapper.map(result.item)), ContentType.Application.Json, HttpStatusCode.Created)
            }
            is CreateResult.Replayed -> {
                call.response.headers.append("Idempotency-Replayed", "true")
                call.respondText(ApiJson.encodeToString(WishlistItemViewMapper.map(result.item)), ContentType.Application.Json, HttpStatusCode.OK)
            }
            is CreateResult.IdempotencyKeyReused -> call.respondApiError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
            CreateResult.InvalidUrl -> call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_URL")
        }
    }
}
