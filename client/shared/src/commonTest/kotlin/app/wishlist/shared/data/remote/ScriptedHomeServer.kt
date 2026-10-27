@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.FakeHomeRepository
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
import kotlin.uuid.Uuid

/**
 * B4 HOME-01/02 wire shapes over a Fake store, like [ScriptedCategoryServer]: paths, query rules,
 * status codes and JSON follow docs/architecture/server/wishlist-item-read-api.md, state rules come
 * from [FakeStore]. The owner is the Bearer token `token-<owner>`. [beforeRespond] runs once, inside
 * the next request (a test's account switch while the answer is on its way).
 */
internal class ScriptedHomeServer(clock: Clock) {
    private val serverSession = MutableAuthSession()
    val store = FakeStore(serverSession, clock, IdGenerator { Uuid.random().toString() })
    private val home = FakeHomeRepository(store)
    private val lock = Mutex()
    var beforeRespond: (suspend () -> Unit)? = null

    val engine = MockEngine { request ->
        val response = lock.withLock { handle(request) }
        beforeRespond?.let { hook -> beforeRespond = null; hook() }
        response
    }

    /** Runs [block] as [owner] on the server store (test setup). */
    suspend fun <T> asOwner(owner: String, block: suspend () -> T): T = lock.withLock {
        if (serverSession.state.value.accountId != owner) serverSession.changeAccount(owner)
        block()
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val owner = request.headers["Authorization"]?.removePrefix("Bearer token-")
            ?: return@run error(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        if (serverSession.state.value.accountId != owner) serverSession.changeAccount(owner)
        val query = request.url.parameters
        when {
            request.method == HttpMethod.Get && request.url.encodedPath == "/v1/home" -> {
                if (!query.isEmpty()) return@run error(HttpStatusCode.BadRequest, "INVALID_HOME_QUERY")
                answer(home.summary()) { summaryJson(it) }
            }
            request.method == HttpMethod.Get && request.url.encodedPath == "/v1/home/action-items" -> {
                val badQuery = "INVALID_HOME_QUERY"
                val group = HomeActionGroup.entries.firstOrNull { it.name == query["group"] }
                    ?: return@run error(HttpStatusCode.BadRequest, badQuery)
                val anchor = query["anchor"]
                val result = if (anchor != null) {
                    if (query["cursor"] != null || query["limit"] != null) return@run error(HttpStatusCode.BadRequest, badQuery)
                    val before = query["before"]?.let { it.toIntOrNull() ?: return@run error(HttpStatusCode.BadRequest, badQuery) } ?: 20
                    val after = query["after"]?.let { it.toIntOrNull() ?: return@run error(HttpStatusCode.BadRequest, badQuery) } ?: 20
                    home.groupAround(group, anchor, before, after)
                } else {
                    if (query["before"] != null || query["after"] != null) return@run error(HttpStatusCode.BadRequest, badQuery)
                    home.group(group, query["cursor"], query["limit"]?.let { it.toIntOrNull() ?: return@run error(HttpStatusCode.BadRequest, badQuery) } ?: 20)
                }
                answer(result) { pageJson(it) }
            }
            else -> error(HttpStatusCode.NotFound, "NOT_FOUND")
        }
    }

    private fun <T> MockRequestHandleScope.answer(result: ClientResult<T>, encode: (T) -> JsonElement) = when (result) {
        is ClientResult.Success -> respond(encode(result.value).toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        is ClientResult.Failure -> error(
            if (result.error.kind == ErrorKind.VALIDATION) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError,
            result.error.code,
        )
    }

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String?) = respond(
        buildJsonObject { put("error", buildJsonObject { put("code", code); put("requestId", "rid-home"); put("details", buildJsonObject {}) }) }.toString(),
        status, headersOf("Content-Type", "application/json"),
    )

    private fun card(item: WishlistItem, anchor: String) = buildJsonObject { put("item", wireItem(item)); put("anchorCursor", anchor) }

    private fun summaryJson(s: HomeSummary) = buildJsonObject {
        put("actionGroups", JsonArray(s.groups.map { g ->
            buildJsonObject {
                put("group", g.group.name); put("count", g.count)
                put("previews", JsonArray(g.previews.map { card(it.item, it.anchorCursor) }))
            }
        }))
        put("recentPurposes", JsonArray(s.recentPurposes.map { p ->
            buildJsonObject {
                put("id", p.id); put("name", p.name); put("colorKey", p.colorKey.uppercase()); put("iconKey", p.iconKey.uppercase())
                put("version", p.version); put("description", p.description); put("candidateCount", p.candidateCount)
                put("activity", buildJsonObject { put("at", p.activity.at.toString()); put("kind", p.activity.kind) })
                put("previews", JsonArray(p.previews.map { buildJsonObject { put("itemId", it.itemId); put("imageUrl", it.imageUrl) } }))
            }
        }))
    }

    private fun pageJson(p: ItemPage) = buildJsonObject {
        put("items", JsonArray(p.items.map { card(it, p.anchorCursors.getValue(it.id)) }))
        put("totalCount", p.totalCount)
        put("previousCursor", p.previousCursor)
        put("nextCursor", p.nextCursor)
        put("requestedAnchorItemId", p.anchor?.requestedItemId)
        put("resolvedAnchorItemId", p.anchor?.resolvedItemId)
        put("anchorResolved", p.anchor?.resolved)
    }
}
