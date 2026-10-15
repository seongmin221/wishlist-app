@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.FakeCategoryRepository
import app.wishlist.shared.data.fake.FakeItemListRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.model.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * B2/B4 wire shapes over a Fake store: the HTTP side (paths, query, headers, status codes, error
 * envelope, JSON) follows docs/architecture/server/category-management-api.md and the B4 ITEM-02
 * contract, while state rules come from [FakeStore]. It checks the client's wire handling only,
 * not the server. The owner is the Bearer token `token-<owner>`.
 */
internal class ScriptedCategoryServer(clock: Clock) {
    private val serverSession = MutableAuthSession()
    val store = FakeStore(serverSession, clock, IdGenerator { Uuid.random().toString() })
    private val categories = FakeCategoryRepository(store)
    private val lists = FakeItemListRepository(store)
    private val lock = Mutex()
    private val seenKeys = mutableSetOf<Pair<String, String>>()
    val statuses = mutableListOf<Int>()

    val engine = MockEngine { request -> lock.withLock { handle(request) } }

    /** Runs [block] as [owner] on the server store (test setup). */
    suspend fun <T> asOwner(owner: String, block: suspend () -> T): T = lock.withLock {
        if (serverSession.state.value.accountId != owner) serverSession.changeAccount(owner)
        block()
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val owner = request.headers["Authorization"]?.removePrefix("Bearer token-")
            ?: return@run error(HttpStatusCode.Unauthorized, ClientError(ErrorKind.UNAUTHENTICATED, "UNAUTHORIZED"))
        if (serverSession.state.value.accountId != owner) serverSession.changeAccount(owner)
        val path = request.url.encodedPath
        val query = request.url.parameters
        when {
            request.method == HttpMethod.Get && path == "/v1/categories" -> {
                val scope = CategoryScope.entries.firstOrNull { it.name == query["scope"] }
                    ?: return@run error(HttpStatusCode.BadRequest, ClientError(ErrorKind.VALIDATION, "INVALID_CATEGORY_QUERY"))
                respondResult(categories.catalog(scope, query["parentId"]), query = true) { catalogJson(it) }
            }
            request.method == HttpMethod.Get && path.startsWith("/v1/custom-categories/") ->
                respondResult(categories.custom(path.substringAfterLast('/')), query = true) { detailJson(it) }
            request.method == HttpMethod.Post && path == "/v1/custom-categories" -> {
                val key = request.headers["Idempotency-Key"]
                    ?: return@run error(HttpStatusCode.BadRequest, ClientError(ErrorKind.VALIDATION, "INVALID_IDEMPOTENCY_KEY"))
                val body = Json.parseToJsonElement(request.bodyText()).jsonObject
                val command = CategoryCommand(
                    body.getValue("parentId").jsonPrimitive.content,
                    body.getValue("name").jsonPrimitive.content,
                    body["description"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                    body["examples"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
                val replay = (owner to key.lowercase()) in seenKeys
                val result = categories.create(command, key)
                if (result is ClientResult.Success) seenKeys += owner to key.lowercase()
                respondResult(result, success = if (replay) HttpStatusCode.OK else HttpStatusCode.Created,
                    extra = if (replay) listOf("Idempotency-Replayed" to "true") else emptyList()) { createdJson(it) }
            }
            request.method == HttpMethod.Patch && path.startsWith("/v1/custom-categories/") -> {
                val body = Json.parseToJsonElement(request.bodyText()).jsonObject
                val patch = CategoryPatch(
                    name = body["name"]?.jsonPrimitive?.content,
                    description = if ("description" in body) DescriptionChange.Set(body["description"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content)
                        else DescriptionChange.Keep,
                    examples = if ("examples" in body) (body["examples"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList() else null,
                )
                val version = body.getValue("expectedVersion").jsonPrimitive.int
                respondResult(categories.update(path.substringAfterLast('/'), version, patch)) { detailJson(it) }
            }
            request.method == HttpMethod.Get && path == "/v1/wishlist-items" -> {
                val categoryId = query["categoryId"]
                    ?: return@run error(HttpStatusCode.BadRequest, ClientError(ErrorKind.VALIDATION, "INVALID_WISHLIST_ITEM_QUERY"))
                val limit = query["limit"]?.toIntOrNull() ?: 40
                respondResult(lists.byCategory(categoryId, query["cursor"], limit), query = true) { pageJson(it) }
            }
            else -> error(HttpStatusCode.NotFound, ClientError(ErrorKind.NOT_FOUND, "NOT_FOUND"))
        }
    }

    private fun <T> MockRequestHandleScope.respondResult(
        result: ClientResult<T>,
        success: HttpStatusCode = HttpStatusCode.OK,
        query: Boolean = false,
        extra: List<Pair<String, String>> = emptyList(),
        encode: (T) -> JsonElement,
    ) = when (result) {
        is ClientResult.Success -> {
            statuses += success.value
            respond(encode(result.value).toString(), success,
                headersOf(*(listOf("Content-Type" to "application/json") + extra).map { it.first to listOf(it.second) }.toTypedArray()))
        }
        is ClientResult.Failure -> error(statusOf(result.error, query), result.error)
    }

    private fun statusOf(error: ClientError, query: Boolean): HttpStatusCode = when (error.kind) {
        ErrorKind.VALIDATION -> if (query || error.code in setOf("INVALID_CATEGORY_ID", "INVALID_IDEMPOTENCY_KEY")) HttpStatusCode.BadRequest
            else HttpStatusCode.UnprocessableEntity
        ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
        ErrorKind.CONFLICT -> HttpStatusCode.Conflict
        ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
        ErrorKind.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
        else -> HttpStatusCode.InternalServerError
    }

    private fun MockRequestHandleScope.error(status: HttpStatusCode, error: ClientError) = run {
        statuses += status.value
        val details = buildJsonObject {
            error.currentVersion?.let { put("currentVersion", it) }
            if (error.fields.isNotEmpty()) put("fields", JsonArray(error.fields.sorted().map(::JsonPrimitive)))
        }
        val body = buildJsonObject {
            put("error", buildJsonObject { put("code", error.code); put("requestId", "rid-test"); put("details", details) })
        }.toString()
        val headers = listOfNotNull("Content-Type" to "application/json",
            error.retryAfterSeconds?.let { "Retry-After" to it.toString() })
        respond(body, status, headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()))
    }

    private fun entryJson(e: CategoryEntry) = buildJsonObject {
        put("id", e.id); put("name", e.name); put("parentId", e.parentId); put("kind", e.rawKind)
        put("displayOrder", e.displayOrder); put("itemCount", e.itemCount)
    }

    private fun catalogJson(c: CategoryCatalog) = buildJsonObject {
        put("scope", c.scope.name); put("taxonomyVersion", c.taxonomyVersion)
        put("groups", JsonArray(c.groups.map { g ->
            buildJsonObject {
                put("id", g.id); put("name", g.name); put("displayOrder", g.displayOrder); put("itemCount", g.itemCount)
                put("categories", JsonArray(g.categories.map(::entryJson)))
            }
        }))
        put("customUsedCount", c.customUsedCount); put("customLimit", c.customLimit)
    }

    private fun detailJson(d: CustomCategoryDetail) = buildJsonObject {
        put("id", d.id); put("name", d.name); put("parentId", d.parentId); put("description", d.description)
        put("examples", JsonArray(d.examples.map(::JsonPrimitive))); put("itemCount", d.itemCount); put("version", d.version)
    }

    private fun createdJson(c: CategoryCreated) = buildJsonObject {
        put("category", detailJson(c.category)); put("customUsedCount", c.customUsedCount); put("customLimit", c.customLimit)
    }

    private fun pageJson(p: ItemPage) = buildJsonObject {
        put("items", JsonArray(p.items.map { buildJsonObject { put("item", wireItem(it)); put("anchorCursor", "a-${it.id}") } }))
        put("totalCount", p.totalCount)
        put("nextCursor", p.nextCursor)
        put("previousCursor", JsonNull)
    }
}

/** The B1~B3 item wire form of [item] (same fields as BASE_ITEM_JSON). */
internal fun wireItem(item: WishlistItem): JsonObject = buildJsonObject {
    fun JsonObjectBuilder.time(key: String, value: Instant?) = put(key, value?.toString())
    put("id", item.id); put("clientSubmissionId", item.clientSubmissionId); put("version", item.version); put("sourceUrl", item.sourceUrl)
    put("product", buildJsonObject {
        put("name", item.product.name); put("imageUrl", item.product.imageUrl)
        put("price", item.product.price?.let { JsonUnquotedLiteral(it.canonical) } ?: JsonNull)
        put("currency", item.product.currency); put("brand", item.product.brand); put("merchant", item.product.merchant)
        time("metadataCheckedAt", item.product.metadataCheckedAt)
        put("nameSource", item.product.nameSource?.name); put("imageSource", item.product.imageSource?.name)
    })
    put("category", buildJsonObject {
        put("id", item.category.id); put("source", item.category.source?.name); put("missingReason", item.category.missingReason?.name)
        put("name", item.category.name); put("parentId", item.category.parentId); put("kind", item.category.kind)
    })
    put("purpose", buildJsonObject {
        put("id", item.purpose.id); put("source", item.purpose.source.name)
        put("name", item.purpose.name); put("colorKey", item.purpose.colorKey); put("iconKey", item.purpose.iconKey)
    })
    put("analysis", buildJsonObject { put("status", item.analysis.status.name); put("failureCode", item.analysis.failureCode) })
    put("reviewStatus", item.reviewStatus.name); put("lifecycleStatus", item.lifecycleStatus.name); put("requiredAction", item.requiredAction.name)
    time("createdAt", item.createdAt); time("updatedAt", item.updatedAt); time("manualCompletionAt", item.manualCompletionAt)
    put("allowedActions", JsonArray(item.allowedActions.map { JsonPrimitive(it.name) }))
    time("clientCreatedAt", item.clientCreatedAt)
}
