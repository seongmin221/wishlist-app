package app

import app.ai.AiClassificationService
import app.ai.CategoryCandidateProvider
import app.ai.ClassificationResult
import app.ai.GatewayResponse
import app.analysis.AnalysisJobReconciler
import app.analysis.GeneralWorkerService
import app.analysis.PendingJobRecovery
import app.analysis.ProcessingOutcome
import app.browser.BrowserWorkerService
import app.budget.BudgetMaintenanceService
import app.budget.LlmBudgetService
import app.extraction.ExtractionResult
import app.extraction.GeneralExtractionProcessor
import app.extraction.HttpFetchResponse
import app.extraction.HttpMetadataExtractor
import app.extraction.UrlSafetyPolicy
import app.maintenance.MaintenanceService
import app.tasks.OutboxDispatcher
import app.testutil.InMemoryTaskQueue
import app.testutil.MetadataFixtures
import app.testutil.analysisScalar
import app.testutil.analysisSql
import app.testutil.withAnalysisDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.InetAddress
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Local runtime with a fake queue and AI: API → general → browser fallback → result, plus maintenance recovery. */
class AnalysisRuntimeFlowTest {
    private val owner = UUID.fromString("00000000-0000-0000-0000-0000000000b5")
    private val productPage = MetadataFixtures.page(MetadataFixtures.product("Neo Daichi",
        """{"@type":"Offer","price":"129000","priceCurrency":"KRW","seller":{"name":"Mizuno Store"}}""", "\"Mizuno\""))
    private val pages = mapOf("https://shop.example/js-shoe" to MetadataFixtures.page(), "https://shop.example/shoe" to productPage)
    private val safety = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }

    private class Runtime(source: DataSource, val queue: InMemoryTaskQueue, val general: GeneralWorkerService,
        val browser: BrowserWorkerService, val maintenance: MaintenanceService) {
        val dispatcher = OutboxDispatcher(source, queue)
    }

    private fun runtime(source: DataSource, process: ((app.analysis.AnalysisClaim) -> ProcessingOutcome)? = null): Runtime {
        val queue = InMemoryTaskQueue()
        val classifier = AiClassificationService(source, LlmBudgetService(source), CategoryCandidateProvider()) { _, _, beforeSend ->
            beforeSend(); GatewayResponse(ClassificationResult.Assigned("C006", null), 500, 20)
        }
        val extractor = HttpMetadataExtractor(safety, MetadataFixtures.fetch(pages))
        val processor = GeneralExtractionProcessor(source, extractor::extract, classifier::classify)
        val rendered = HttpMetadataExtractor(safety) { _, _ -> HttpFetchResponse(200, mapOf("content-type" to "text/html"), productPage) }
        val browser = BrowserWorkerService(source, { _ -> (rendered.extract("https://shop.example/shoe") as ExtractionResult.Complete).metadata }, classifier::classify)
        val dispatcher = OutboxDispatcher(source, queue)
        val maintenance = MaintenanceService({ limit, deadline -> dispatcher.dispatchPending(limit, deadline) },
            { AnalysisJobReconciler(source).reconcileExpired() }, PendingJobRecovery(source, queue)::recover,
            BudgetMaintenanceService(source) { }::runOnce)
        return Runtime(source, queue, GeneralWorkerService(source, process ?: processor::process), browser, maintenance)
    }

    private suspend fun HttpClient.create(url: String): UUID {
        val response = post("/v1/wishlist-items") {
            header("Idempotency-Key", UUID.randomUUID().toString()); contentType(ContentType.Application.Json)
            setBody("""{"sourceUrl":"$url"}""")
        }
        return UUID.fromString(Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
    }

    private suspend fun HttpClient.product(id: UUID): JsonObject =
        Json.parseToJsonElement(get("/v1/wishlist-items/$id").bodyAsText()).jsonObject

    private fun latestTask(source: DataSource, item: UUID) = analysisScalar(source, """select e.task_name from outbox_events e
        join analysis_jobs j on j.id=e.analysis_job_id where j.wishlist_item_id='$item' order by e.created_at desc, e.id desc limit 1""")!!

    private fun withFlow(process: ((app.analysis.AnalysisClaim) -> ProcessingOutcome)? = null, block: suspend HttpClient.(DataSource, Runtime) -> Unit) =
        withAnalysisDatabase { source ->
            val runtime = runtime(source, process)
            testApplication {
                application { routing { apiRoutes(source, { id -> runtime.dispatcher.dispatchEvent(id) }) { owner } } }
                client.block(source, runtime)
            }
        }

    @Test fun `create to ready through general and browser with fake queue`() = withFlow { source, runtime ->
        val id = create("https://shop.example/js-shoe")
        assertEquals(1, runtime.queue.liveNames().size)
        runtime.queue.deliver(runtime.queue.liveNames().single(), runtime.general::runGeneral)
        assertEquals("BROWSER_PENDING", analysisScalar(source, "select stage from analysis_jobs where wishlist_item_id='$id'"))
        assertEquals(1, runtime.maintenance.runOnce().publishedEvents)
        runtime.queue.deliver(latestTask(source, id), runtime.browser::runBrowser)
        val body = product(id)
        assertEquals("READY", body.getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
        val product = body.getValue("product").jsonObject
        assertEquals("Neo Daichi", product.getValue("name").jsonPrimitive.content)
        assertEquals("Mizuno", product.getValue("brand").jsonPrimitive.content)
        assertEquals("129000.0000", product.getValue("price").jsonPrimitive.content)
        assertEquals("KRW", product.getValue("currency").jsonPrimitive.content)
        assertEquals("Mizuno Store", product.getValue("merchant").jsonPrimitive.content)
        assertNotNull(product.getValue("metadataCheckedAt").jsonPrimitive.content)
    }

    @Test fun `lost general task is recovered by maintenance`() = withFlow { source, runtime ->
        val id = create("https://shop.example/shoe")
        runtime.queue.drop(runtime.queue.liveNames().single())
        analysisSql(source, "update analysis_jobs set updated_at=clock_timestamp()-interval '6 minutes' where wishlist_item_id='$id'")
        val report = runtime.maintenance.runOnce()
        assertEquals(1, report.pendingRescheduled)
        assertEquals(emptyList(), report.failedSteps)
        assertEquals(1, runtime.maintenance.runOnce().publishedEvents)
        val task = runtime.queue.liveNames().single()
        assertEquals(true, task.endsWith("-pending-1"), task)
        runtime.queue.deliver(task, runtime.general::runGeneral)
        assertEquals("READY", product(id).getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("1", analysisScalar(source, "select attempt_count from analysis_jobs where wishlist_item_id='$id'"))
    }

    @Test fun `retryable fault acknowledges and backs off`() {
        var faults = 1
        lateinit var real: (app.analysis.AnalysisClaim) -> ProcessingOutcome
        withFlow({ claim -> if (faults-- > 0) error("temporary infrastructure failure") else real(claim) }) { source, runtime ->
            val classifier = AiClassificationService(source, LlmBudgetService(source), CategoryCandidateProvider()) { _, _, beforeSend ->
                beforeSend(); GatewayResponse(ClassificationResult.Assigned("C006", null), 500, 20)
            }
            real = GeneralExtractionProcessor(source, HttpMetadataExtractor(safety, MetadataFixtures.fetch(pages))::extract, classifier::classify)::process
            val id = create("https://shop.example/shoe")
            assertEquals(app.analysis.WorkerDisposition.ACKNOWLEDGE, runtime.queue.deliver(runtime.queue.liveNames().single(), runtime.general::runGeneral))
            assertEquals(1, runtime.maintenance.runOnce().publishedEvents)
            val retry = runtime.queue.created.last()
            assertNotNull(retry.scheduleAt)
            runtime.queue.deliver(retry.name, runtime.general::runGeneral)
            assertEquals("READY", product(id).getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun `exhausted generation becomes failed retryable with read metadata`() {
        lateinit var flaky: (app.analysis.AnalysisClaim) -> ProcessingOutcome
        withFlow({ claim -> flaky(claim) }) { source, runtime ->
            flaky = GeneralExtractionProcessor(source, HttpMetadataExtractor(safety, MetadataFixtures.fetch(pages))::extract) { _, _ ->
                error("model endpoint unavailable")
            }::process
            val id = create("https://shop.example/shoe")
            repeat(3) { round ->
                if (round > 0) assertEquals(1, runtime.maintenance.runOnce().publishedEvents)
                runtime.queue.deliver(runtime.queue.liveNames().single(), runtime.general::runGeneral)
            }
            val body = product(id)
            assertEquals("FAILED_RETRYABLE", body.getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
            val product = body.getValue("product").jsonObject
            assertEquals("Neo Daichi", product.getValue("name").jsonPrimitive.content)
            assertEquals("129000.0000", product.getValue("price").jsonPrimitive.content)
            assertNotNull(product.getValue("metadataCheckedAt").jsonPrimitive.content)
        }
    }
}
