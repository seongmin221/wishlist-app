package app.http

import app.apiRoutes
import app.candidates.CandidateException
import app.testutil.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class CandidateRoutesTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-0000000000bb")

    private fun withApi(source: DataSource, storage: InMemoryMediaStorage = InMemoryMediaStorage(), block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            installApiHttpSupport()
            routing { apiRoutes(source, { }, mediaWiring(source, storage)) { call -> call.request.headers["Test-Owner"]?.let(UUID::fromString) } }
        }
        client.block()
    }

    private suspend fun HttpClient.send(method: HttpMethod, path: String, body: String? = null, key: UUID? = null, as_: UUID? = owner) =
        request(path) {
            this.method = method
            as_?.let { header("Test-Owner", it.toString()) }
            key?.let { header("Idempotency-Key", it.toString()) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String, details: JsonObject = JsonObject(emptyMap())) {
        assertEquals(status, this.status, bodyAsText())
        val error = json().getValue("error").jsonObject
        assertEquals(code, error.getValue("code").jsonPrimitive.content)
        assertEquals(details, error.getValue("details"))
        assertEquals(headers["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
    }

    private fun moveBody(version: Int, vararg items: Triple<UUID, Int, UUID?>) = buildJsonObject {
        put("expectedVersion", version)
        putJsonArray("items") { items.forEach { (id, v, p) -> addJsonObject { put("id", id.toString()); put("expectedVersion", v); put("expectedPurposeId", p?.toString()) } } }
    }.toString()

    @Test fun `candidate page returns WishlistItemDto elements and facets only on request`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val (media, key) = insertMedia(source, owner); storage.put(key, byteArrayOf(1))
        val target = insertPurpose(source, owner, "목적", null)
        val other = insertPurpose(source, owner, "다른", null)
        val photo = seedItem(source, owner, ItemSeed(media = media, imageSource = "USER", purpose = other, purposeSource = "AI"))
        setCreatedAt(source, photo, "2026-10-24T02:00:00Z")
        val plain = seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", category = null, categorySource = null,
            missingReason = "AI_ABSTAINED", name = null, nameSource = null))
        setCreatedAt(source, plain, "2026-10-24T01:00:00Z")
        seedItem(source, owner, ItemSeed(purpose = target, purposeSource = "USER"))
        withApi(source, storage) {
            val withFacets = send(HttpMethod.Get, "/v1/purposes/$target/candidate-items?includeCategoryFacets=true")
            assertEquals(HttpStatusCode.OK, withFacets.status, withFacets.bodyAsText())
            val body = withFacets.json()
            assertEquals(listOf("items", "totalCount", "nextCursor", "categoryFacets", "unassignedCategoryCount"), body.keys.toList())
            val items = body.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(listOf(photo, plain).map { it.toString() }, items.map { it.getValue("id").jsonPrimitive.content })
            for (item in items) {
                val detail = send(HttpMethod.Get, "/v1/wishlist-items/${item.getValue("id").jsonPrimitive.content}").json()
                val expected = JsonObject(detail - "deletionImpact")
                assertEquals(expected.keys.toList(), item.keys.toList(), "same WishlistItemDto key order")
                assertEquals(JsonObject(expected - "product"), JsonObject(item - "product"))
                assertEquals(JsonObject(expected.getValue("product").jsonObject - "imageUrl"), JsonObject(item.getValue("product").jsonObject - "imageUrl"))
            }
            assertFalse("anchorCursor" in items[0]); assertFalse("deletionImpact" in items[0]); assertFalse("item" in items[0])
            assertTrue(items[0].getValue("product").jsonObject.getValue("imageUrl").jsonPrimitive.content.isNotBlank(), "user photo is signed")
            assertEquals(other.toString(), items[0].getValue("purpose").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(2, body.getValue("totalCount").jsonPrimitive.int)
            assertEquals(JsonNull, body.getValue("nextCursor"))
            assertEquals(buildJsonArray { addJsonObject { put("categoryId", "C026"); put("parentId", "G003"); put("count", 1) } }, body.getValue("categoryFacets"))
            assertEquals(1, body.getValue("unassignedCategoryCount").jsonPrimitive.int)

            for (path in listOf("/v1/purposes/$target/candidate-items", "/v1/purposes/$target/candidate-items?includeCategoryFacets=false")) {
                assertEquals(listOf("items", "totalCount", "nextCursor"), send(HttpMethod.Get, path).json().keys.toList(), path)
            }
            val first = send(HttpMethod.Get, "/v1/purposes/$target/candidate-items?limit=1").json()
            val cursor = first.getValue("nextCursor").jsonPrimitive.content
            val second = send(HttpMethod.Get, "/v1/purposes/$target/candidate-items?limit=1&includeCategoryFacets=true&cursor=$cursor").json()
            assertEquals(plain.toString(), second.getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(JsonNull, second.getValue("nextCursor"))
            assertEquals(0, send(HttpMethod.Get, "/v1/purposes/$target/candidate-items?categoryId=C019&includeCategoryFacets=true").json()
                .getValue("totalCount").jsonPrimitive.int)

            val base = "/v1/purposes/$target/candidate-items"
            send(HttpMethod.Get, base, as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Get, "/v1/purposes/nope/candidate-items", as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Get, "/v1/purposes/nope/candidate-items").assertError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID")
            for (query in listOf("categoryId=C026&parentId=G003", "limit=0", "limit=101", "x=1", "limit=1&limit=2", "categoryId=C999", "parentId=G999",
                "includeCategoryFacets=1")) send(HttpMethod.Get, "$base?$query").assertError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_QUERY")
            send(HttpMethod.Get, "$base?cursor=$cursor&categoryId=C026").assertError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_CURSOR")
            send(HttpMethod.Get, "/v1/purposes/$other/candidate-items?cursor=$cursor").assertError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_CURSOR")
            send(HttpMethod.Get, "$base?cursor=$cursor", as_ = UUID.randomUUID()).assertError(HttpStatusCode.BadRequest, "INVALID_CANDIDATE_CURSOR")
            send(HttpMethod.Get, "$base?categoryId=${insertCustomCategory(source, UUID.randomUUID())}").assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            send(HttpMethod.Get, base, as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
        }
    }

    @Test fun `candidate move returns stored results and documented errors`() = withAnalysisDatabase { source ->
        val target = insertPurpose(source, owner, "목적", null)
        val from = insertPurpose(source, owner, "이전", null)
        val item = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "AI"))
        val second = seedItem(source, owner)
        val foreign = seedItem(source, UUID.randomUUID())
        val path = "/v1/purposes/$target/candidate-moves"
        withApi(source) {
            val key = UUID.randomUUID()
            val body = moveBody(1, Triple(item, 3, from), Triple(second, 3, null))
            val moved = send(HttpMethod.Post, path, body, key)
            assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
            assertNull(moved.headers["Idempotency-Replayed"])
            val expectedItems = listOf(item, second).sortedWith(sqlUuidOrder).map { id ->
                buildJsonObject { put("id", id.toString()); put("version", 4); put("purposeId", target.toString()) } }
            val expectedPurposes = listOf(from to (0 to 2), target to (2 to 3)).sortedWith(compareBy(sqlUuidOrder) { it.first }).map { (id, v) ->
                buildJsonObject { put("id", id.toString()); put("candidateCount", v.first); put("membershipVersion", v.second) } }
            val expected = buildJsonObject { put("items", JsonArray(expectedItems)); put("purposes", JsonArray(expectedPurposes)) }
            assertEquals(expected, moved.json())
            assertEquals(listOf("items", "purposes"), moved.json().keys.toList())
            val replay = send(HttpMethod.Post, path, moveBody(1, Triple(second, 3, null), Triple(item, 3, from)), key)
            assertEquals(HttpStatusCode.OK, replay.status); assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(moved.bodyAsText(), replay.bodyAsText())
            send(HttpMethod.Post, path, moveBody(1, Triple(item, 3, from)), key).assertError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")

            send(HttpMethod.Post, path, body, UUID.randomUUID(), as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Post, "/v1/purposes/x/candidate-moves", body, UUID.randomUUID()).assertError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID")
            send(HttpMethod.Post, path, body).assertError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
            send(HttpMethod.Post, path, "[]", UUID.randomUUID()).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_CANDIDATE_INPUT")
            send(HttpMethod.Post, path, """{"expectedVersion":1,"items":[]}""", UUID.randomUUID())
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_CANDIDATE_INPUT", buildJsonObject { putJsonArray("fields") { add("items") } })
            send(HttpMethod.Post, path, """{"expectedVersion":0,"items":[]}""", UUID.randomUUID())
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_CANDIDATE_INPUT", buildJsonObject { putJsonArray("fields") { add("expectedVersion"); add("items") } })

            val fresh = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "USER"))
            send(HttpMethod.Post, path, moveBody(2, Triple(fresh, 3, from), Triple(foreign, 3, null)), UUID.randomUUID())
                .assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            send(HttpMethod.Post, path, moveBody(2, Triple(fresh, 3, from)), UUID.randomUUID())
                .assertError(HttpStatusCode.Conflict, "PURPOSE_VERSION_CONFLICT", buildJsonObject { put("currentVersion", 1) })
            send(HttpMethod.Post, path, moveBody(1, Triple(fresh, 3, null)), UUID.randomUUID()).assertError(HttpStatusCode.Conflict,
                "WISHLIST_ITEM_PURPOSE_CONFLICT", buildJsonObject { put("itemId", fresh.toString()); put("currentVersion", 3) })
            send(HttpMethod.Post, path, moveBody(1, Triple(fresh, 2, from)), UUID.randomUUID()).assertError(HttpStatusCode.Conflict,
                "WISHLIST_ITEM_VERSION_CONFLICT", buildJsonObject { put("itemId", fresh.toString()); put("currentVersion", 3) })
            send(HttpMethod.Post, path, moveBody(1, Triple(item, 4, target)), UUID.randomUUID()).assertError(HttpStatusCode.Conflict,
                "WISHLIST_ITEM_STATE_CONFLICT", buildJsonObject { put("itemId", item.toString()); put("currentVersion", 4) })
            send(HttpMethod.Post, path, body, UUID.randomUUID(), as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
        }
    }

    private fun deletionBody(version: Int, token: String) = """{"expectedVersion":$version,"impactToken":"$token"}"""

    @Test fun `deleted category replays after live repository returns null`() = withAnalysisDatabase { source ->
        val category = insertCustomCategory(source, owner)
        val items = List(3) { seedItem(source, owner, ItemSeed(customCategory = category)) }
        val base = "/v1/custom-categories/$category"
        withApi(source) {
            val page = send(HttpMethod.Get, "$base/deletion-impact?limit=2")
            assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
            val body = page.json()
            assertEquals(listOf("version", "impactToken", "affectedCount", "items", "nextCursor"), body.keys.toList())
            assertEquals(3, body.getValue("affectedCount").jsonPrimitive.int)
            val first = body.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(2, first.size)
            assertTrue(first.none { "anchorCursor" in it || "deletionImpact" in it })
            val detail = send(HttpMethod.Get, "/v1/wishlist-items/${first[0].getValue("id").jsonPrimitive.content}").json()
            assertEquals(JsonObject(detail - "deletionImpact"), first[0], "elements are the plain WishlistItemDto")
            val token = body.getValue("impactToken").jsonPrimitive.content
            val version = body.getValue("version").jsonPrimitive.int
            val rest = send(HttpMethod.Get, "$base/deletion-impact?cursor=${body.getValue("nextCursor").jsonPrimitive.content}").json()
            assertEquals(JsonNull, rest.getValue("nextCursor")); assertEquals(1, rest.getValue("items").jsonArray.size)

            send(HttpMethod.Get, "$base/deletion-impact", as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Get, "/v1/custom-categories/x/deletion-impact").assertError(HttpStatusCode.BadRequest, "INVALID_CATEGORY_ID")
            send(HttpMethod.Get, "$base/deletion-impact?limit=0").assertError(HttpStatusCode.BadRequest, "INVALID_DELETION_QUERY")
            send(HttpMethod.Get, "$base/deletion-impact?cursor=nope").assertError(HttpStatusCode.BadRequest, "INVALID_DELETION_CURSOR")
            send(HttpMethod.Get, "$base/deletion-impact", as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(version, token), as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Delete, "/v1/custom-categories/x", deletionBody(version, token)).assertError(HttpStatusCode.BadRequest, "INVALID_CATEGORY_ID")
            send(HttpMethod.Delete, base, "{}").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DELETION_INPUT")
            send(HttpMethod.Delete, base, deletionBody(0, "x")).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DELETION_INPUT",
                buildJsonObject { putJsonArray("fields") { add("expectedVersion"); add("impactToken") } })
            send(HttpMethod.Delete, base, deletionBody(version, "0".repeat(64))).assertError(HttpStatusCode.Conflict, "CATEGORY_DELETION_IMPACT_CONFLICT")
            send(HttpMethod.Delete, base, deletionBody(version, token), as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")

            val deleted = send(HttpMethod.Delete, base, deletionBody(version, token))
            assertEquals(HttpStatusCode.NoContent, deleted.status, deleted.bodyAsText()); assertEquals("", deleted.bodyAsText())
            send(HttpMethod.Get, base).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            val snapshot = itemSnapshot(source, items)
            val replay = send(HttpMethod.Delete, base, deletionBody(version, token))
            assertEquals(HttpStatusCode.NoContent, replay.status, replay.bodyAsText()); assertEquals("", replay.bodyAsText())
            assertEquals(snapshot, itemSnapshot(source, items)); assertEquals(1, deletionReceipts(source))
            send(HttpMethod.Delete, base, deletionBody(version + 1, token)).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(version, "0".repeat(64))).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(version, token), as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            send(HttpMethod.Get, "$base/deletion-impact").assertError(HttpStatusCode.NotFound, "CATEGORY_NOT_FOUND")
            assertEquals(snapshot, itemSnapshot(source, items)); assertEquals(1, deletionReceipts(source))
        }
    }

    @Test fun `deleted purpose replays after active lookup returns null`() = withAnalysisDatabase { source ->
        val purpose = insertPurpose(source, owner, "목적", null)
        val items = List(2) { seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI")) }
        val base = "/v1/purposes/$purpose"
        withApi(source) {
            val impact = send(HttpMethod.Get, "$base/deletion-impact").json()
            assertEquals(listOf("version", "impactToken", "affectedCount", "items", "nextCursor"), impact.keys.toList())
            assertEquals(2, impact.getValue("affectedCount").jsonPrimitive.int)
            assertEquals(purpose.toString(), impact.getValue("items").jsonArray[0].jsonObject.getValue("purpose").jsonObject.getValue("id").jsonPrimitive.content)
            val token = impact.getValue("impactToken").jsonPrimitive.content
            send(HttpMethod.Get, "/v1/purposes/x/deletion-impact").assertError(HttpStatusCode.BadRequest, "INVALID_PURPOSE_ID")
            send(HttpMethod.Get, "$base/deletion-impact?limit=x").assertError(HttpStatusCode.BadRequest, "INVALID_DELETION_QUERY")
            send(HttpMethod.Get, "$base/deletion-impact", as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(1, "0".repeat(64))).assertError(HttpStatusCode.Conflict, "PURPOSE_DELETION_IMPACT_CONFLICT")
            send(HttpMethod.Delete, base, deletionBody(1, token), as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            send(HttpMethod.Delete, base, """{"expectedVersion":1}""").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DELETION_INPUT")

            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, base, deletionBody(1, token)).status)
            send(HttpMethod.Get, base).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            val snapshot = itemSnapshot(source, items)
            val membership = purposeState(source, purpose)
            val replay = send(HttpMethod.Delete, base, deletionBody(1, token))
            assertEquals(HttpStatusCode.NoContent, replay.status, replay.bodyAsText()); assertEquals("", replay.bodyAsText())
            assertEquals(snapshot, itemSnapshot(source, items)); assertEquals(membership, purposeState(source, purpose)); assertEquals(1, deletionReceipts(source))
            send(HttpMethod.Delete, base, deletionBody(2, token)).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(1, "1".repeat(64))).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            send(HttpMethod.Delete, base, deletionBody(1, token), as_ = UUID.randomUUID()).assertError(HttpStatusCode.NotFound, "PURPOSE_NOT_FOUND")
            send(HttpMethod.Get, "/v1/wishlist-items/${items[0]}").json().getValue("purpose").jsonObject.let {
                assertEquals(JsonNull, it.getValue("id")); assertEquals("USER", it.getValue("source").jsonPrimitive.content)
            }
            assertEquals(snapshot, itemSnapshot(source, items)); assertEquals(1, deletionReceipts(source))
        }
    }

    @Test fun `impact cursor reports a changed impact as a conflict`() = withAnalysisDatabase { source ->
        val category = insertCustomCategory(source, owner)
        val items = List(2) { seedItem(source, owner, ItemSeed(customCategory = category)) }
        withApi(source) {
            val cursor = send(HttpMethod.Get, "/v1/custom-categories/$category/deletion-impact?limit=1").json().getValue("nextCursor").jsonPrimitive.content
            seedItem(source, owner, ItemSeed(customCategory = category))
            send(HttpMethod.Get, "/v1/custom-categories/$category/deletion-impact?limit=1&cursor=$cursor")
                .assertError(HttpStatusCode.Conflict, "CATEGORY_DELETION_IMPACT_CONFLICT")
            assertEquals(2, items.size)
        }
    }

    @Test fun `blocking candidate routes run outside the route executor and cancellation propagates`() {
        val requests: List<suspend HttpClient.() -> HttpResponse> = listOf(
            { get("/v1/purposes/${UUID.randomUUID()}/candidate-items") },
            { post("/v1/purposes/${UUID.randomUUID()}/candidate-moves") {
                header("Idempotency-Key", UUID.randomUUID().toString())
                setBody("""{"expectedVersion":1,"items":[{"id":"${UUID.randomUUID()}","expectedVersion":1,"expectedPurposeId":null}]}""")
            } },
            { get("/v1/custom-categories/${UUID.randomUUID()}/deletion-impact") },
            { delete("/v1/custom-categories/${UUID.randomUUID()}") { setBody("""{"expectedVersion":1,"impactToken":"${"a".repeat(64)}"}""") } },
            { get("/v1/purposes/${UUID.randomUUID()}/deletion-impact") },
            { delete("/v1/purposes/${UUID.randomUUID()}") { setBody("""{"expectedVersion":1,"impactToken":"${"a".repeat(64)}"}""") } },
        )
        for (request in requests) {
            val register: io.ktor.server.routing.Route.(() -> Unit) -> Unit = { block ->
                val fail = { block(); throw CandidateException("PURPOSE_NOT_FOUND") }
                candidateRoutes(CandidateHandlers(list = { _, _, _ -> fail() }, move = { _, _, _, _ -> fail() },
                    impact = { _, _, _, _ -> fail() }, delete = { _, _, _, _, _ -> fail() }), NO_MEDIA) { UUID.randomUUID() }
            }
            assertBlockingRouteIo(register, request)
            assertRouteCancellation(register, request)
        }
    }
}
