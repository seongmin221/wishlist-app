package app

import app.ai.AiClassificationService
import app.ai.CategoryCandidateProvider
import app.ai.ClassificationResult
import app.ai.GatewayResponse
import app.analysis.AnalysisJobReconciler
import app.analysis.GeneralWorkerService
import app.analysis.PendingJobRecovery
import app.budget.BudgetMaintenanceService
import app.budget.LlmBudgetService
import app.extraction.GeneralExtractionProcessor
import app.extraction.HttpMetadataExtractor
import app.extraction.UrlSafetyPolicy
import app.http.installApiHttpSupport
import app.maintenance.MaintenanceService
import app.tasks.OutboxDispatcher
import app.testutil.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Server half of "a share saved while signed out is sent after sign-in": a delayed create with an old share time and a
 * lost response, then failure → reanalysis → review → edit with a photo → delete, through HTTP with fake auth and queue.
 * The device-side LocalSubmission belongs to the client bundles.
 */
class ItemMutationFlowTest {
    private val owner = UUID.fromString("00000000-0000-4000-8000-0000000000b7")
    private val page = MetadataFixtures.page(MetadataFixtures.product("Neo Daichi",
        """{"@type":"Offer","price":"129000","priceCurrency":"KRW","seller":{"name":"Mizuno Store"}}""", "\"Mizuno\""))
    private val safety = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.version() = getValue("version").jsonPrimitive.int
    private fun JsonObject.at(vararg path: String): JsonElement = path.fold(this as JsonElement) { e, key -> e.jsonObject.getValue(key) }

    @Test fun `delayed save then failure reanalysis review photo edit and delete`() = withAnalysisDatabase { source ->
        var aiAvailable = false
        val queue = InMemoryTaskQueue()
        val classifier = AiClassificationService(source, LlmBudgetService(source), CategoryCandidateProvider()) { _, _, beforeSend ->
            beforeSend(); GatewayResponse(ClassificationResult.Assigned("C006", null), 500, 20)
        }
        val processor = GeneralExtractionProcessor(source, HttpMetadataExtractor(safety, MetadataFixtures.fetch(mapOf("https://shop.example/shoe" to page)))::extract) { claim, metadata ->
            if (!aiAvailable) error("model endpoint unavailable") else classifier.classify(claim, metadata)
        }
        val general = GeneralWorkerService(source, processor::process)
        val dispatcher = OutboxDispatcher(source, queue)
        val maintenance = MaintenanceService({ limit, deadline -> dispatcher.dispatchPending(limit, deadline) },
            { AnalysisJobReconciler(source).reconcileExpired() }, PendingJobRecovery(source, queue)::recover, BudgetMaintenanceService(source) { }::runOnce)
        val storage = InMemoryMediaStorage()
        val (media, mediaKey) = insertMedia(source, owner)
        storage.put(mediaKey, byteArrayOf(1))

        testApplication {
            application {
                installApiHttpSupport()
                routing { apiRoutes(source, { id -> dispatcher.dispatchEvent(id) }, mediaWiring(source, storage)) { call ->
                    if (call.request.headers["Test-Auth"] == "signed-in") owner else null } }
            }
            suspend fun HttpClient.call(method: HttpMethod, path: String, body: String? = null, key: UUID? = null) = request(path) {
                this.method = method; header("Test-Auth", "signed-in")
                key?.let { header("Idempotency-Key", it.toString()) }
                body?.let { contentType(ContentType.Application.Json); setBody(it) }
            }

            // Delayed send after sign-in: an old share time, the first response lost, the same key resent.
            val shared = Instant.parse("2026-10-12T09:00:00Z")
            val submission = UUID.randomUUID()
            val createBody = """{"sourceUrl":"https://shop.example/shoe","clientCreatedAt":"$shared"}"""
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/wishlist-items") { header("Idempotency-Key", submission.toString()); setBody(createBody) }.status)
            val created = client.call(HttpMethod.Post, "/v1/wishlist-items", createBody, submission)
            assertEquals(HttpStatusCode.Created, created.status)
            val replay = client.call(HttpMethod.Post, "/v1/wishlist-items", createBody, submission)
            assertEquals(HttpStatusCode.OK, replay.status)
            val id = replay.json().getValue("id").jsonPrimitive.content
            assertEquals(created.json().getValue("id").jsonPrimitive.content, id)
            assertEquals(shared.toString(), replay.json().getValue("clientCreatedAt").jsonPrimitive.content)
            assertEquals("1", analysisScalar(source, "select count(*) from wishlist_items where owner_id='$owner'"))

            // The first generation exhausts its budget while the model is down.
            repeat(3) { round ->
                if (round > 0) assertEquals(1, maintenance.runOnce().publishedEvents)
                queue.deliver(queue.liveNames().single(), general::runGeneral)
            }
            var item = client.call(HttpMethod.Get, "/v1/wishlist-items/$id").json()
            assertEquals("FAILED_RETRYABLE", item.at("analysis", "status").jsonPrimitive.content)
            assertTrue("REANALYZE" in item.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content })

            // Reanalysis: one new generation; the replayed key adds nothing.
            aiAvailable = true
            val attempt = UUID.randomUUID()
            item = client.call(HttpMethod.Post, "/v1/wishlist-items/$id/analysis-attempts", null, attempt).json()
            assertEquals("PROCESSING", item.at("analysis", "status").jsonPrimitive.content)
            assertEquals("true", client.call(HttpMethod.Post, "/v1/wishlist-items/$id/analysis-attempts", null, attempt).headers["Idempotency-Replayed"])
            queue.deliver(queue.liveNames().single(), general::runGeneral)
            item = client.call(HttpMethod.Get, "/v1/wishlist-items/$id").json()
            assertEquals("READY", item.at("analysis", "status").jsonPrimitive.content)
            assertEquals("CLASSIFICATION_REVIEW", item.getValue("requiredAction").jsonPrimitive.content)

            // Review: defer, and the item does not come back to the review group.
            item = client.call(HttpMethod.Put, "/v1/wishlist-items/$id/review-decision", """{"expectedVersion":${item.version()},"decision":"DEFER"}""").json()
            assertEquals("DEFERRED", item.getValue("reviewStatus").jsonPrimitive.content)
            val reviewGroup = client.call(HttpMethod.Get, "/v1/home/action-items?group=CLASSIFICATION_REVIEW").json()
            assertEquals(0, reviewGroup.getValue("totalCount").jsonPrimitive.int)

            // Edit with a photo, then remove it again.
            val stale = item.version()
            item = client.call(HttpMethod.Patch, "/v1/wishlist-items/$id", """{"expectedVersion":$stale,"name":"내 운동화","mediaId":"$media"}""").json()
            assertEquals(media.toString(), item.at("product", "imageMediaId").jsonPrimitive.content)
            assertEquals("USER", item.at("product", "imageSource").jsonPrimitive.content)
            assertEquals(HttpStatusCode.Conflict, client.call(HttpMethod.Patch, "/v1/wishlist-items/$id", """{"expectedVersion":$stale,"brand":null}""").status)
            item = client.call(HttpMethod.Patch, "/v1/wishlist-items/$id", """{"expectedVersion":${item.version()},"mediaId":null}""").json()
            assertEquals(JsonNull, item.at("product", "imageMediaId"))
            assertEquals("내 운동화", item.at("product", "name").jsonPrimitive.content)
            assertNotNull(mediaColumns(source, media)["unreferenced"])

            // Delete twice, then the item is gone.
            assertEquals(HttpStatusCode.NoContent, client.call(HttpMethod.Delete, "/v1/wishlist-items/$id").status)
            assertEquals(HttpStatusCode.NoContent, client.call(HttpMethod.Delete, "/v1/wishlist-items/$id").status)
            assertEquals(HttpStatusCode.NotFound, client.call(HttpMethod.Get, "/v1/wishlist-items/$id").status)
            val home = client.call(HttpMethod.Get, "/v1/home").json()
            assertTrue(home.getValue("actionGroups").jsonArray.all { it.jsonObject.getValue("count").jsonPrimitive.int == 0 }, home.toString())
        }
    }
}
