package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.*
import app.wishlist.shared.model.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** B3 wire simulator. State rules use FakeStore; exact HTTP/wire expectations have separate assertions. */
internal class ScriptedPurposeServer(private val store: FakeStore) {
    val statuses = mutableListOf<Int>()
    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = if (request.method == HttpMethod.Post || request.method == HttpMethod.Patch) Json.parseToJsonElement(request.bodyText()).jsonObject else null
        when {
            path == "/v1/purposes" && request.method == HttpMethod.Get -> answer(store.purposeSummary()) { catalog(it) }
            path == "/v1/purposes" && request.method == HttpMethod.Post -> {
                val b = body!!
                val command = PurposeCommand(b.getValue("name").jsonPrimitive.content, b.text("description"), b.getValue("colorKey").jsonPrimitive.content.lowercase(), b.getValue("iconKey").jsonPrimitive.content.lowercase())
                val key = request.headers["Idempotency-Key"]!!
                val replay = request.headers["Authorization"] to key
                val result = store.createPurpose(command, key)
                val success = if (replay in receipts) HttpStatusCode.OK else HttpStatusCode.Created
                if (result is ClientResult.Success) receipts += replay
                answer(result, success) { buildJsonObject { put("purpose", detail(it.purpose)); put("activeCount", it.activeCount); put("purposeLimit", it.purposeLimit) } }
            }
            path.startsWith("/v1/purposes/") && request.method == HttpMethod.Get -> answer(store.purposeDetail(path.substringAfterLast('/'))) { detail(it) }
            path.startsWith("/v1/purposes/") && request.method == HttpMethod.Patch -> {
                val b = body!!
                val patch = PurposePatch(b.text("name"), if ("description" in b) DescriptionChange.Set(b.text("description")) else DescriptionChange.Keep,
                    b.text("colorKey")?.lowercase(), b.text("iconKey")?.lowercase())
                answer(store.updatePurpose(path.substringAfterLast('/'), b.getValue("expectedVersion").jsonPrimitive.int, patch)) { detail(it) }
            }
            path == "/v1/wishlist-items" -> answer(store.itemsByPurpose(request.url.parameters["purposeId"]!!, request.url.parameters["cursor"], request.url.parameters["limit"]!!.toInt())) { p ->
                buildJsonObject {
                    put("items", JsonArray(p.items.map { buildJsonObject { put("item", wireItem(it)) } }))
                    put("totalCount", p.totalCount); put("nextCursor", p.nextCursor)
                }
            }
            else -> error("Unexpected purpose request $request")
        }
    }
    private val receipts = mutableSetOf<Pair<String?, String>>()
    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun <T> MockRequestHandleScope.answer(result: ClientResult<T>, success: HttpStatusCode = HttpStatusCode.OK, encode: (T) -> JsonElement): io.ktor.client.request.HttpResponseData = when (result) {
        is ClientResult.Success -> { statuses += success.value; json(success, encode(result.value).toString()) }
        is ClientResult.Failure -> {
            val error = result.error
            val status = when (error.kind) {
                ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
                ErrorKind.CONFLICT -> HttpStatusCode.Conflict
                ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
                ErrorKind.VALIDATION -> if (error.code?.contains("CURSOR") == true || error.code?.endsWith("_ID") == true || error.code == "INVALID_IDEMPOTENCY_KEY") HttpStatusCode.BadRequest else HttpStatusCode.UnprocessableEntity
                ErrorKind.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
                else -> HttpStatusCode.InternalServerError
            }
            statuses += status.value
            json(status, buildJsonObject { put("error", buildJsonObject {
                put("code", error.code); put("requestId", "purpose-request")
                put("details", buildJsonObject {
                    error.currentVersion?.let { put("currentVersion", it) }
                    if (error.fields.isNotEmpty()) put("fields", JsonArray(error.fields.map(::JsonPrimitive)))
                })
            }) }.toString(), *listOfNotNull(error.retryAfterSeconds?.let { "Retry-After" to "$it" }).toTypedArray())
        }
    }
    private fun activity(a: PurposeActivity) = buildJsonObject { put("at", a.at.toString()); put("kind", a.kind) }
    private fun detail(d: PurposeDetail) = buildJsonObject {
        put("id", d.id); put("name", d.name); put("description", d.description)
        put("colorKey", d.colorKey.uppercase()); put("iconKey", d.iconKey.uppercase())
        put("candidateCount", d.candidateCount); put("membershipVersion", d.membershipVersion); put("version", d.version)
        put("activity", activity(d.activity)); put("createdAt", d.createdAt.toString()); put("updatedAt", d.updatedAt.toString())
        put("allowedActions", JsonArray(d.allowedActions.map(::JsonPrimitive)))
    }
    private fun catalog(c: PurposeCatalog) = buildJsonObject {
        put("projection", "SUMMARY"); put("activeCount", c.activeCount); put("purposeLimit", c.purposeLimit); put("nextCursor", JsonNull)
        put("archiveSummary", buildJsonObject { put("count", c.archiveSummary.count); put("recentTitles", JsonArray(c.archiveSummary.recentTitles.map(::JsonPrimitive))) })
        put("purposes", JsonArray(c.purposes.map { p -> buildJsonObject {
            put("id", p.id); put("name", p.name); put("description", p.description); put("colorKey", p.colorKey.uppercase()); put("iconKey", p.iconKey.uppercase())
            put("version", p.version); put("candidateCount", p.candidateCount); put("activity", activity(p.activity))
            put("previews", JsonArray(p.previews.map { buildJsonObject { put("itemId", it.itemId); put("imageUrl", it.imageUrl) } }))
        } }))
    }
}
