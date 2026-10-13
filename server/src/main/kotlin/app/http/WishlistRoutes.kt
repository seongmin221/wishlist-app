package app.http

import app.common.parseCanonicalUuid
import app.media.MediaUrlSigner
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import app.wishlist.GetWishlistItemService
import app.wishlist.WishlistItem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import java.time.Instant
import java.util.UUID

fun Route.wishlistRoutes(
    service: CreateWishlistItemService,
    detailService: GetWishlistItemService,
    signer: MediaUrlSigner,
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) = wishlistRoutes(service::create, detailService::get, signer, ownerResolver)

fun Route.wishlistRoutes(
    create: (UUID, UUID, String, Instant?) -> CreateResult,
    getItem: (UUID, UUID) -> WishlistItem?,
    signer: MediaUrlSigner,
    ownerResolver: suspend (ApplicationCall) -> UUID?,
) {
    // Creation replays return the current item, which may carry a user image after B7, so every path signs.
    suspend fun render(item: WishlistItem): String {
        val urls = withContext(Dispatchers.IO) { signer.signAll(listOf(item).mediaKeys()) }
        return ApiJson.encodeToString(WishlistItemViewMapper.map(item, urls))
    }
    get("/v1/wishlist-items/{id}") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val itemId = parseCanonicalUuid(call.parameters["id"])
            ?: return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
        val item = withContext(Dispatchers.IO) { getItem(owner, itemId) }
            ?: return@get call.respondApiError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
        call.respondText(render(item), ContentType.Application.Json)
    }

    post("/v1/wishlist-items") {
        val owner = ownerResolver(call) ?: return@post call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val key = parseCanonicalUuid(call.request.headers["Idempotency-Key"])
            ?: return@post call.respondApiError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val request = when (val parsed = parseCreateRequest(call.receiveText())) {
            is CreateRequestParseResult.Valid -> parsed
            is CreateRequestParseResult.Invalid -> return@post call.respondApiError(HttpStatusCode.UnprocessableEntity, parsed.code.name)
        }
        when (val result = withContext(Dispatchers.IO) { create(owner, key, request.sourceUrl, request.clientCreatedAt) }) {
            is CreateResult.Created -> {
                call.response.headers.append(HttpHeaders.Location, "/v1/wishlist-items/${result.itemId}")
                call.respondText(render(result.item), ContentType.Application.Json, HttpStatusCode.Created)
            }
            is CreateResult.Replayed -> {
                call.response.headers.append("Idempotency-Replayed", "true")
                call.respondText(render(result.item), ContentType.Application.Json, HttpStatusCode.OK)
            }
            is CreateResult.IdempotencyKeyReused -> call.respondApiError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
            CreateResult.InvalidUrl -> call.respondApiError(HttpStatusCode.UnprocessableEntity, "INVALID_URL")
        }
    }
}
