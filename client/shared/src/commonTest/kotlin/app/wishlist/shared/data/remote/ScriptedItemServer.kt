package app.wishlist.shared.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/**
 * Test-only scripted responder for ITEM-01/ITEM-03. Written by hand from the server's documented
 * wire shapes (fixture baseline: develop 1c6d949); it never calls FakeStore or evaluateItem.
 * Owners are told apart by the Bearer token `token-<ownerId>`; submission keys are per owner.
 */
internal class ScriptedItemServer {
    enum class State { PROCESSING, READY, DELETED }
    class Stored(val id: String, val key: String, val sourceUrl: String, val clientCreatedAt: String?, var state: State)

    private val owners = mutableMapOf<String, MutableMap<String, Stored>>()
    private var counter = 100
    val statuses = mutableListOf<Int>()

    /** Runs after the request is received and before the response is produced (in-flight tests). */
    var beforeRespond: (suspend () -> Unit)? = null

    /** Overrides the next response body (status stays 200) for malformed-response tests. */
    var rawBodyOnce: String? = null

    val engine = MockEngine { request -> handle(request) }

    fun find(owner: String, id: String): Stored = owners.getValue(owner).values.first { it.id == id }
    fun complete(owner: String, id: String) { find(owner, id).state = State.READY }
    fun delete(owner: String, id: String) { find(owner, id).state = State.DELETED }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        beforeRespond?.invoke()
        val owner = request.headers["Authorization"]?.removePrefix("Bearer token-")
            ?: return@run error(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        val items = owners.getOrPut(owner) { mutableMapOf() }
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Post && path == "/v1/wishlist-items" -> create(request, items)
            request.method == HttpMethod.Get && path.startsWith("/v1/wishlist-items/") -> {
                val id = path.removePrefix("/v1/wishlist-items/")
                val stored = items.values.firstOrNull { it.id == id }?.takeIf { it.state != State.DELETED }
                if (stored == null) error(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
                else ok(HttpStatusCode.OK, stored)
            }
            else -> error(HttpStatusCode.NotFound, "NOT_FOUND")
        }
    }.also { statuses += it.statusCode.value }

    private fun MockRequestHandleScope.create(request: HttpRequestData, items: MutableMap<String, Stored>) = run {
        val key = request.headers["Idempotency-Key"] ?: return@run error(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
        val url = (body["sourceUrl"] as JsonPrimitive).content
        val existing = items[key]
        when {
            existing == null -> {
                val stored = Stored("00000000-0000-4000-8000-${(++counter).toString().padStart(12, '0')}", key, url,
                    (body["clientCreatedAt"] as? JsonPrimitive)?.content, State.PROCESSING)
                items[key] = stored
                ok(HttpStatusCode.Created, stored)
            }
            existing.sourceUrl != url -> error(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
            else -> ok(HttpStatusCode.OK, existing, "Idempotency-Replayed" to "true")
        }
    }

    private fun MockRequestHandleScope.ok(status: HttpStatusCode, item: Stored, vararg extra: Pair<String, String>) =
        json(status, rawBodyOnce?.also { rawBodyOnce = null } ?: itemBody(item), *extra)

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) =
        json(status, """{"error":{"code":"$code","requestId":"req-1","details":{}}}""")

    private fun itemBody(s: Stored): String {
        val (version, analysis, name, categoryId, review, required, lifecycle, actions) = when (s.state) {
            State.PROCESSING -> Shape(1, "PROCESSING", "null", "null", "NOT_REQUIRED", "ANALYSIS_IN_PROGRESS", "ACTIVE", """["DELETE"]""")
            State.READY -> Shape(2, "READY", "\"Headphones\"", "\"C026\"", "CONFIRMED", "NONE", "ACTIVE", """["EDIT","DELETE"]""")
            State.DELETED -> Shape(3, "READY", "\"Headphones\"", "\"C026\"", "CONFIRMED", "NONE", "DELETED", "[]")
        }
        val createdClient = s.clientCreatedAt?.let { "\"$it\"" } ?: "null"
        return """{"id":"${s.id}","clientSubmissionId":"${s.key}","version":$version,"sourceUrl":${JsonPrimitive(s.sourceUrl)},
"product":{"name":$name,"imageUrl":null,"price":null,"currency":null,"brand":null,"merchant":null,"metadataCheckedAt":null,"nameSource":null,"imageSource":null},
"category":{"id":$categoryId,"source":null,"missingReason":null,"name":null,"parentId":null,"kind":null},
"purpose":{"id":null,"source":"UNASSIGNED"},"analysis":{"status":"$analysis","failureCode":null},
"reviewStatus":"$review","lifecycleStatus":"$lifecycle","requiredAction":"$required",
"createdAt":"2026-10-07T00:00:00Z","updatedAt":"2026-10-07T00:00:00Z","manualCompletionAt":null,
"allowedActions":$actions,"clientCreatedAt":$createdClient}"""
    }

    private data class Shape(
        val version: Int, val analysis: String, val name: String, val categoryId: String,
        val review: String, val required: String, val lifecycle: String, val actions: String,
    )
}
