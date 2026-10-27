package app.http

import app.apiRoutes
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import app.wishlist.ItemMutationException
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

class WishlistItemMutationRoutesTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-0000000000aa")

    private fun withApi(source: DataSource, storage: InMemoryMediaStorage = InMemoryMediaStorage(), dispatched: MutableList<UUID> = mutableListOf(),
                        block: suspend HttpClient.() -> Unit) = testApplication {
        application {
            installApiHttpSupport()
            routing { apiRoutes(source, { dispatched += it }, mediaWiring(source, storage)) { call -> if (call.request.headers["Test-Auth"] == "true") owner else null } }
        }
        client.block()
    }

    private suspend fun HttpClient.send(method: HttpMethod, path: String, body: String? = null, key: UUID? = null, auth: Boolean = true) =
        request(path) {
            this.method = method
            if (auth) header("Test-Auth", "true")
            key?.let { header("Idempotency-Key", it.toString()) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error() = json().getValue("error").jsonObject

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String): JsonObject {
        assertEquals(status, this.status, bodyAsText())
        return error().also { assertEquals(code, it.getValue("code").jsonPrimitive.content) }
    }

    @Test fun `patch manual completion and review return the single item representation`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val (media, key) = insertMedia(source, owner)
        storage.put(key, byteArrayOf(1))
        val purpose = insertPurpose(source, owner, "출퇴근", null)
        val item = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val partial = seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", category = null, categorySource = null,
            missingReason = "AI_ABSTAINED"))
        val review = seedItem(source, owner)
        withApi(source, storage) {
            val edited = send(HttpMethod.Patch, "/v1/wishlist-items/$item", """{"expectedVersion":3,"name":"내 이름","mediaId":"$media"}""")
            assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
            val body = edited.json()
            assertEquals(4, body.getValue("version").jsonPrimitive.int)
            val product = body.getValue("product").jsonObject
            assertEquals("내 이름", product.getValue("name").jsonPrimitive.content)
            assertEquals(media.toString(), product.getValue("imageMediaId").jsonPrimitive.content)
            assertTrue(product.getValue("imageUrl").jsonPrimitive.content.isNotBlank(), "user photo is signed")
            assertEquals(1, body.getValue("deletionImpact").jsonObject.getValue("purpose").jsonObject.getValue("candidateCount").jsonPrimitive.int)

            val done = send(HttpMethod.Put, "/v1/wishlist-items/$partial/manual-completion", """{"expectedVersion":3,"name":"직접","categoryId":"C026"}""")
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            assertEquals("CONFIRMED", done.json().getValue("reviewStatus").jsonPrimitive.content)
            assertNotEquals(JsonNull, done.json().getValue("manualCompletionAt"))

            val deferred = send(HttpMethod.Put, "/v1/wishlist-items/$review/review-decision", """{"expectedVersion":3,"decision":"DEFER"}""")
            assertEquals(HttpStatusCode.OK, deferred.status, deferred.bodyAsText())
            assertEquals("DEFERRED", deferred.json().getValue("reviewStatus").jsonPrimitive.content)
        }
    }

    @Test fun `mutation errors use stable codes statuses and details`() = withAnalysisDatabase { source ->
        val item = seedItem(source, owner)
        val processing = seedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"))
        val other = seedItem(source, UUID.randomUUID())
        withApi(source) {
            val path = "/v1/wishlist-items/$item"
            send(HttpMethod.Patch, path, """{"expectedVersion":3,"name":"a"}""", auth = false).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Patch, "/v1/wishlist-items/not-an-id", """{"expectedVersion":3,"name":"a"}""").assertError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
            val fields = send(HttpMethod.Patch, path, """{"expectedVersion":3,"purposeId":1,"mediaId":2}""").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT")
            assertEquals(listOf("mediaId", "purposeId"), fields.getValue("details").jsonObject.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(JsonObject(emptyMap()), send(HttpMethod.Patch, path, """{"expectedVersion":3,"price":1}""")
                .assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT").getValue("details"))
            val text = send(HttpMethod.Patch, path, """{"expectedVersion":3,"name":"${"가".repeat(201)}"}""").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT")
            assertEquals(listOf("name"), text.getValue("details").jsonObject.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            send(HttpMethod.Patch, "/v1/wishlist-items/$other", """{"expectedVersion":3,"name":"a"}""").assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            val stale = send(HttpMethod.Patch, path, """{"expectedVersion":2,"name":"a"}""").assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_VERSION_CONFLICT")
            assertEquals(3, stale.getValue("details").jsonObject.getValue("currentVersion").jsonPrimitive.int)
            val state = send(HttpMethod.Patch, "/v1/wishlist-items/$processing", """{"expectedVersion":3,"name":"a"}""").assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT")
            assertEquals(3, state.getValue("details").jsonObject.getValue("currentVersion").jsonPrimitive.int)
            send(HttpMethod.Patch, path, """{"expectedVersion":3,"categoryId":"${UUID.randomUUID()}"}""").assertError(HttpStatusCode.Conflict, "CATEGORY_NOT_AVAILABLE")
            send(HttpMethod.Patch, path, """{"expectedVersion":3,"purposeId":"${UUID.randomUUID()}"}""").assertError(HttpStatusCode.Conflict, "PURPOSE_NOT_AVAILABLE")
            send(HttpMethod.Patch, path, """{"expectedVersion":3,"mediaId":"${UUID.randomUUID()}"}""").assertError(HttpStatusCode.Conflict, "MEDIA_NOT_AVAILABLE")
            send(HttpMethod.Put, "$path/manual-completion", """{"expectedVersion":3,"name":"a"}""").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT")
            send(HttpMethod.Put, "$path/manual-completion", """{"expectedVersion":3,"name":"a","categoryId":"C026"}""").assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT")
            send(HttpMethod.Put, "$path/review-decision", """{"expectedVersion":3,"decision":"MAYBE"}""").assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT")
            send(HttpMethod.Put, "/v1/wishlist-items/$processing/review-decision", """{"expectedVersion":3,"decision":"CONFIRM"}""").assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT")
        }
    }

    @Test fun `delete is idempotent for the owner and hidden from others`() = withAnalysisDatabase { source ->
        val item = seedItem(source, owner)
        val other = seedItem(source, UUID.randomUUID())
        withApi(source) {
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/v1/wishlist-items/$item").status)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/v1/wishlist-items/$item").status)
            send(HttpMethod.Get, "/v1/wishlist-items/$item").assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            send(HttpMethod.Delete, "/v1/wishlist-items/$other").assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            send(HttpMethod.Delete, "/v1/wishlist-items/${UUID.randomUUID()}").assertError(HttpStatusCode.NotFound, "WISHLIST_ITEM_NOT_FOUND")
            send(HttpMethod.Delete, "/v1/wishlist-items/x").assertError(HttpStatusCode.BadRequest, "INVALID_WISHLIST_ITEM_ID")
            send(HttpMethod.Delete, "/v1/wishlist-items/$item", auth = false).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            val archived = seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED"))
            send(HttpMethod.Delete, "/v1/wishlist-items/$archived").assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT")
        }
    }

    @Test fun `reanalysis starts replays and limits attempts over http`() = withAnalysisDatabase { source ->
        val dispatched = mutableListOf<UUID>()
        val item = seedItem(source, owner, ItemSeed(analysis = "FAILED_RETRYABLE", review = "NOT_REQUIRED", category = null,
            categorySource = null, missingReason = "EXTRACTION_UNRESOLVED", name = null, nameSource = null))
        val second = seedItem(source, owner, ItemSeed(analysis = "FAILED_RETRYABLE", review = "NOT_REQUIRED"))
        withApi(source, dispatched = dispatched) {
            val path = "/v1/wishlist-items/$item/analysis-attempts"
            val key = UUID.randomUUID()
            send(HttpMethod.Post, path, auth = false, key = key).assertError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
            send(HttpMethod.Post, path).assertError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY")
            send(HttpMethod.Post, path, """{"force":true}""", key).assertError(HttpStatusCode.UnprocessableEntity, "INVALID_WISHLIST_ITEM_INPUT")
            val started = send(HttpMethod.Post, path, "{}", key)
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
            assertNull(started.headers["Idempotency-Replayed"])
            assertEquals("PROCESSING", started.json().getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(1, dispatched.size)
            val replay = send(HttpMethod.Post, path, null, key)
            assertEquals(HttpStatusCode.OK, replay.status); assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(1, dispatched.size)
            send(HttpMethod.Post, "/v1/wishlist-items/$second/analysis-attempts", null, key).assertError(HttpStatusCode.Conflict, "IDEMPOTENCY_KEY_REUSED")
            send(HttpMethod.Post, path, null, UUID.randomUUID()).assertError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT")
            repeat(9) { analysisSql(source, """insert into mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint,wishlist_item_id)
                values ('$owner','REANALYZE_WISHLIST_ITEM','${UUID.randomUUID()}','${"f".repeat(64)}','$item')""") }
            val limited = send(HttpMethod.Post, "/v1/wishlist-items/$second/analysis-attempts", null, UUID.randomUUID())
            limited.assertError(HttpStatusCode.TooManyRequests, "WISHLIST_ITEM_REANALYSIS_RATE_LIMITED")
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
        }
    }

    @Test fun `blocking mutations run outside the route executor and cancellation propagates`() {
        val requests: List<suspend HttpClient.() -> HttpResponse> = listOf(
            { patch("/v1/wishlist-items/${UUID.randomUUID()}") { setBody("""{"expectedVersion":1,"name":"a"}""") } },
            { put("/v1/wishlist-items/${UUID.randomUUID()}/manual-completion") { setBody("""{"expectedVersion":1,"name":"a","categoryId":"C026"}""") } },
            { put("/v1/wishlist-items/${UUID.randomUUID()}/review-decision") { setBody("""{"expectedVersion":1,"decision":"CONFIRM"}""") } },
            { delete("/v1/wishlist-items/${UUID.randomUUID()}") },
            { post("/v1/wishlist-items/${UUID.randomUUID()}/analysis-attempts") { header("Idempotency-Key", UUID.randomUUID().toString()) } },
        )
        for (request in requests) {
            val register: io.ktor.server.routing.Route.(() -> Unit) -> Unit = { block ->
                val fail = { block(); throw ItemMutationException("WISHLIST_ITEM_NOT_FOUND") }
                wishlistItemMutationRoutes(ItemMutationHandlers(
                    edit = { _, _, _, _ -> fail() }, completeManually = { _, _, _, _ -> fail() }, decideReview = { _, _, _, _, _ -> fail() },
                    delete = { _, _ -> fail() }, reanalyze = { _, _, _ -> fail() },
                ), NO_MEDIA) { UUID.randomUUID() }
            }
            assertBlockingRouteIo(register, request)
            assertRouteCancellation(register, request)
        }
    }

    @Test fun `public api registers the mutation routes`() = withAnalysisDatabase { source ->
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/p").createdItemId
        withApi(source) {
            for ((method, path) in listOf(HttpMethod.Patch to "/v1/wishlist-items/$item", HttpMethod.Put to "/v1/wishlist-items/$item/manual-completion",
                HttpMethod.Put to "/v1/wishlist-items/$item/review-decision")) {
                assertEquals(HttpStatusCode.UnprocessableEntity, send(method, path, "[]").status, path)
            }
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/v1/wishlist-items/$item/analysis-attempts").status)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/v1/wishlist-items/$item").status)
        }
    }
}
