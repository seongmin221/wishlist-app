package app.http

import app.apiRoutes
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

class DuplicateRoutesTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-0000000000b9")

    private fun withApi(source: DataSource, storage: InMemoryMediaStorage = InMemoryMediaStorage(), block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            installApiHttpSupport()
            routing { apiRoutes(source, { }, mediaWiring(source, storage)) { call -> call.request.headers["Test-Owner"]?.let(UUID::fromString) } }
        }
        client.block()
    }

    private suspend fun HttpClient.send(method: HttpMethod, path: String, body: String? = null, key: String? = null, as_: UUID? = owner) =
        request(path) {
            this.method = method
            as_?.let { header("Test-Owner", it.toString()) }
            key?.let { header("Idempotency-Key", it) }
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

    @Test fun `candidate list returns both items as WishlistItemDto with signed images`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val (media, key) = insertMedia(source, owner); storage.put(key, byteArrayOf(1))
        val purpose = insertPurpose(source, owner, "가을 트레일 러닝", null)
        val new = seedAnalyzedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val existing = seedAnalyzedItem(source, owner, ItemSeed(media = media, imageSource = "USER", purpose = purpose, purposeSource = "USER"))
        setCreatedAt(source, existing, "2026-10-01T00:00:00Z")
        val pair = seedPair(source, owner, new, existing)
        withApi(source, storage) {
            val response = send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.json()
            assertEquals(listOf("item", "candidates", "totalCount", "nextCursor"), body.keys.toList())
            assertEquals(new.toString(), body.getValue("item").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(1, body.getValue("item").jsonObject.getValue("duplicateCandidates").jsonObject.getValue("matchCount").jsonPrimitive.int)
            val candidate = body.getValue("candidates").jsonArray.single().jsonObject
            assertEquals(listOf("id", "kind", "bases", "version", "allowedDecisions", "existingItem"), candidate.keys.toList())
            assertEquals(pair.toString(), candidate.getValue("id").jsonPrimitive.content)
            assertEquals("MATCH", candidate.getValue("kind").jsonPrimitive.content)
            assertEquals(listOf("CANONICAL_URL", "SOURCE_URL"), candidate.getValue("bases").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("KEEP_BOTH", "DELETE_NEW", "DELETE_EXISTING"), candidate.getValue("allowedDecisions").jsonArray.map { it.jsonPrimitive.content })
            val existingDto = candidate.getValue("existingItem").jsonObject
            assertEquals(existing.toString(), existingDto.getValue("id").jsonPrimitive.content)
            assertTrue(existingDto.getValue("product").jsonObject.getValue("imageUrl").jsonPrimitive.content.isNotBlank(), "user photo is signed")
            assertEquals(2, existingDto.getValue("deletionImpact").jsonObject.getValue("purpose").jsonObject.getValue("candidateCount").jsonPrimitive.int)
            assertEquals(1, body.getValue("totalCount").jsonPrimitive.int)
            assertEquals(JsonNull, body.getValue("nextCursor"))
        }
    }

    @Test fun `candidate list validates auth path query and cursor`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val other = seedAnalyzedItem(source, owner)
        repeat(2) { seedPair(source, owner, new, seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") }) }
        withApi(source) {
            send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates", as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Get, "/v1/wishlist-items/not-a-uuid/duplicate-candidates").assertError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
            for (query in listOf("limit=0", "limit=51", "limit=x", "limit=1&limit=2", "unknown=1")) {
                send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates?$query").assertError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_QUERY")
            }
            val first = send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates?limit=1").json()
            val cursor = first.getValue("nextCursor").jsonPrimitive.content
            val second = send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates?limit=1&cursor=$cursor").json()
            assertEquals(1, second.getValue("candidates").jsonArray.size)
            assertEquals(JsonNull, second.getValue("nextCursor"))
            send(HttpMethod.Get, "/v1/wishlist-items/$other/duplicate-candidates?cursor=$cursor").assertError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_CURSOR")
            send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates?cursor=$cursor", as_ = UUID.randomUUID())
                .assertError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_CURSOR")
            send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates?cursor=%21%21").assertError(HttpStatusCode.BadRequest, "INVALID_DUPLICATE_CURSOR")
            send(HttpMethod.Get, "/v1/wishlist-items/$new/duplicate-candidates", as_ = UUID.randomUUID())
                .assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
        }
    }

    private fun decisionBody(pair: UUID, decision: String, candidateVersion: Int = 1, itemVersion: Int? = 3) = buildJsonObject {
        put("candidateId", pair.toString()); put("decision", decision); put("expectedCandidateVersion", candidateVersion)
        itemVersion?.let { put("expectedItemVersion", it) }
    }.toString()

    @Test fun `decision returns the result and replays it with a header`() = withAnalysisDatabase { source ->
        val purpose = insertPurpose(source, owner, "가을 트레일 러닝", null)
        val new = seedAnalyzedItem(source, owner)
        val existing = seedAnalyzedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER"))
        setCreatedAt(source, existing, "2026-10-01T00:00:00Z")
        val pair = seedPair(source, owner, new, existing)
        val key = UUID.randomUUID().toString()
        withApi(source) {
            val path = "/v1/wishlist-items/$new/duplicate-decisions"
            val first = send(HttpMethod.Put, path, decisionBody(pair, "DELETE_EXISTING"), key)
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertNull(first.headers["Idempotency-Replayed"])
            val body = first.json()
            assertEquals(listOf("candidate", "deletedItemId", "remainingItemIds", "purposes"), body.keys.toList())
            val candidate = body.getValue("candidate").jsonObject
            assertEquals(listOf("id", "decision", "decidedAt", "version"), candidate.keys.toList())
            assertEquals("DELETE_EXISTING", candidate.getValue("decision").jsonPrimitive.content)
            assertEquals(2, candidate.getValue("version").jsonPrimitive.int)
            assertEquals(existing.toString(), body.getValue("deletedItemId").jsonPrimitive.content)
            assertEquals(listOf(new.toString()), body.getValue("remainingItemIds").jsonArray.map { it.jsonPrimitive.content })
            val purposeDto = body.getValue("purposes").jsonArray.single().jsonObject
            assertEquals(purpose.toString(), purposeDto.getValue("id").jsonPrimitive.content)
            assertEquals(0, purposeDto.getValue("candidateCount").jsonPrimitive.int)
            val replay = send(HttpMethod.Put, path, decisionBody(pair, "DELETE_EXISTING"), key)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(body, replay.json())
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null), key).assertError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
        }
    }

    @Test fun `decision validates auth path key body and maps service errors`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val existing = seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") }
        val pair = seedPair(source, owner, new, existing)
        val path = "/v1/wishlist-items/$new/duplicate-decisions"
        val key = { UUID.randomUUID().toString() }
        withApi(source) {
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null), key(), as_ = null).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Put, "/v1/wishlist-items/x/duplicate-decisions", decisionBody(pair, "KEEP_BOTH", itemVersion = null), key())
                .assertError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null)).assertError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null), "nope").assertError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
            for (raw in listOf("[]", "{}", """{"candidateId":"$pair","decision":"KEEP_BOTH"}""",
                """{"candidateId":"$pair","decision":"KEEP_BOTH","expectedCandidateVersion":1,"extra":1}""")) {
                send(HttpMethod.Put, path, raw, key()).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DUPLICATE_DECISION_INPUT")
            }
            fun fields(vararg names: String) = buildJsonObject { putJsonArray("fields") { names.forEach { add(it) } } }
            send(HttpMethod.Put, path, """{"candidateId":"x","decision":"MERGE","expectedCandidateVersion":0}""", key())
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DUPLICATE_DECISION_INPUT", fields("candidateId", "decision", "expectedCandidateVersion"))
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = 3), key())
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DUPLICATE_DECISION_INPUT", fields("expectedItemVersion"))
            send(HttpMethod.Put, path, decisionBody(pair, "DELETE_NEW", itemVersion = null), key())
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_DUPLICATE_DECISION_INPUT", fields("expectedItemVersion"))
            send(HttpMethod.Put, path, decisionBody(UUID.randomUUID(), "KEEP_BOTH", itemVersion = null), key())
                .assertError(HttpStatusCode.NotFound, "DUPLICATE_CANDIDATE_NOT_FOUND")
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null), key(), as_ = UUID.randomUUID())
                .assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", 5, null), key())
                .assertError(HttpStatusCode.Conflict, "DUPLICATE_CANDIDATE_VERSION_CONFLICT", buildJsonObject { put("currentVersion", 1) })
            send(HttpMethod.Put, path, decisionBody(pair, "DELETE_EXISTING", itemVersion = 7), key()).assertError(HttpStatusCode.Conflict,
                "WISHLIST_ITEM_VERSION_CONFLICT", buildJsonObject { put("itemId", existing.toString()); put("currentVersion", 3) })
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", itemVersion = null), key()).status)
            send(HttpMethod.Put, path, decisionBody(pair, "KEEP_BOTH", 2, null), key())
                .assertError(HttpStatusCode.Conflict, "DUPLICATE_DECISION_NOT_ALLOWED", buildJsonObject { put("currentVersion", 2) })
        }
    }
}
