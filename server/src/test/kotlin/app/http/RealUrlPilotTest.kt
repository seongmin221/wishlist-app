package app.http

import app.DatabaseFactory
import app.ai.AiClassificationService
import app.ai.OpenAiConfig
import app.ai.OpenAiResponsesGateway
import app.ai.TaxonomyCatalog
import app.analysis.GeneralWorkerService
import app.budget.LlmBudgetService
import app.extraction.ExtractionResult
import app.extraction.GeneralExtractionProcessor
import app.extraction.HttpMetadataExtractor
import app.extraction.SafeHttpTransport
import app.extraction.UrlSafetyPolicy
import app.wishlist.CreateWishlistItemService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.testcontainers.containers.PostgreSQLContainer
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt-in diagnostic: live shop HTML and OpenAI are intentionally not part of the normal suite. */
class RealUrlPilotTest {
    @Test fun `eight real URLs traverse local create and worker HTTP path`() {
        assumeTrue(System.getenv("RUN_REAL_URL_PILOT") == "1", "live URL pilot is opt-in")
        val key = requireNotNull(System.getenv("OPENAI_API_KEY"))
        val cases = checkNotNull(javaClass.classLoader.getResourceAsStream("evaluations/user-links-pilot-draft.jsonl"))
            .bufferedReader().use { lines -> lines.readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject } }
        assertEquals(8, cases.size)

        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start()
            DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            val catalog = TaxonomyCatalog.loadV1()
            val gateway = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna", key, allowLocalAlias = true))
            val classifier = AiClassificationService(source, LlmBudgetService(source, modelSnapshot = "gpt-5.6-luna", allowLocalAlias = true),
                { catalog.snapshot(catalog.categories.map { it.id }.toSet()) }, gateway::classify)
            val transport = SafeHttpTransport()
            val httpTrace = mutableListOf<String>()
            val extractor = HttpMetadataExtractor(UrlSafetyPolicy(), { url, addresses ->
                transport.fetch(url, addresses).also { response ->
                    httpTrace.add("${response.status}:${response.headers["content-type"]}:${response.headers["location"]}")
                }
            })
            var extraction: String? = null
            var error: String? = null
            val processor = GeneralExtractionProcessor(source, { url ->
                try {
                    extractor.extract(url).also { extraction = when (it) {
                        is ExtractionResult.Complete -> "COMPLETE:${it.metadata.title}"
                        ExtractionResult.NeedsBrowser -> "NEEDS_BROWSER"
                        ExtractionResult.Partial -> "PARTIAL"
                    } }
                } catch (failure: Exception) {
                    error = "${failure.javaClass.simpleName}:${failure.message}"
                    throw failure
                }
            }, classifier::classify)
            val worker = GeneralWorkerService(source, processor::process)
            val owner = UUID.randomUUID()
            var correct = 0

            testApplication {
                application { routing {
                    wishlistRoutes(CreateWishlistItemService(source)) { owner }
                    workerRoutes(worker)
                } }
                for (case in cases) {
                    extraction = null
                    error = null
                    httpTrace.clear()
                    val id = case.getValue("id").jsonPrimitive.content
                    val url = case.getValue("source_url").jsonPrimitive.content
                    val expected = case.getValue("provisional_category_id").jsonPrimitive.content
                    val created = client.post("/v1/wishlist-items") {
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":${kotlinx.serialization.json.JsonPrimitive(url)}}""")
                    }
                    assertEquals(HttpStatusCode.Created, created.status, id)
                    val jobId = source.connection.use { c -> c.prepareStatement(
                        "select j.id from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id where i.source_url=?",
                    ).use { s -> s.setString(1, url); s.executeQuery().use { r -> r.next(); r.getObject(1, UUID::class.java) } } }
                    var workerStatus: HttpStatusCode? = null
                    var attempts = 0
                    for (attempt in 1..3) {
                        attempts = attempt
                        workerStatus = client.post("/internal/worker/general") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"jobId":"$jobId","generation":1}""")
                        }.status
                        if (workerStatus != HttpStatusCode.ServiceUnavailable) break
                    }
                    val state = source.connection.use { c -> c.prepareStatement(
                        "select i.analysis_status,i.predicted_category_id,i.product_name,i.analysis_failure_code,j.stage from wishlist_items i join analysis_jobs j on j.wishlist_item_id=i.id where j.id=?",
                    ).use { s -> s.setObject(1, jobId); s.executeQuery().use { r -> r.next();
                        listOf(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))
                    } } }
                    if (state[0] == "READY" && state[1] == expected) correct++
                    println("REAL_URL_PILOT id=$id attempts=$attempts http=${workerStatus?.value} expected=$expected status=${state[0]} predicted=${state[1]} stage=${state[4]} extraction=$extraction fetch=$httpTrace name=${state[2]} failure=${state[3]} exception=$error")
                }
            }
            assertEquals(8, correct, "live URL path should classify all confirmed examples")
        }
    }
}
