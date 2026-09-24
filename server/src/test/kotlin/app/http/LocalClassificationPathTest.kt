package app.http

import app.DatabaseFactory
import app.ai.AiClassificationService
import app.ai.GatewayResponse
import app.ai.OpenAiConfig
import app.ai.OpenAiResponsesGateway
import app.ai.TaxonomyCatalog
import app.analysis.GeneralWorkerService
import app.budget.LlmBudgetService
import app.extraction.ExtractionResult
import app.extraction.GeneralExtractionProcessor
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.testcontainers.containers.PostgreSQLContainer

class LocalClassificationPathTest {
    @Test fun `opt in real OpenAI smoke follows create and worker HTTP path`() {
        if (System.getenv("RUN_REAL_OPENAI_SMOKE") != "1") return
        val key = requireNotNull(System.getenv("OPENAI_API_KEY"))
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start()
            DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            val catalog = TaxonomyCatalog.loadV1()
            val gateway = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna", key, allowLocalAlias = true))
            var observed: GatewayResponse? = null
            val classifier = AiClassificationService(source, LlmBudgetService(source, modelSnapshot = "gpt-5.6-luna", allowLocalAlias = true),
                { catalog.snapshot(catalog.categories.map { it.id }.toSet()) },
                { metadata, candidates -> gateway.classify(metadata, candidates).also { observed = it } })
            val processor = GeneralExtractionProcessor(source,
                { url -> ExtractionResult.Complete(Metadata("CAYL cap", null, null, url)) }, classifier::classify)
            testApplication {
                application { routing {
                    wishlistRoutes(CreateWishlistItemService(source)) { UUID.fromString("00000000-0000-0000-0000-000000000001") }
                    workerRoutes(GeneralWorkerService(source, processor::process))
                } }
                val created = client.post("/v1/wishlist-items") {
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"sourceUrl":"https://example.com/cayl-cap"}""")
                }
                assertEquals(HttpStatusCode.Created, created.status)
                val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1, UUID::class.java) } }
                val processed = client.post("/internal/worker/general") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"jobId":"$jobId","generation":1}""")
                }
                assertEquals(HttpStatusCode.NoContent, processed.status)
                assertEquals(true, observed?.inputTokens in 0..2000)
                assertEquals(true, observed?.outputTokens in 0..80)
                source.connection.use { c -> c.createStatement().executeQuery("select analysis_status,predicted_category_id from wishlist_items").use { r ->
                    r.next()
                    assertEquals("READY", r.getString(1))
                    assertEquals("C011", r.getString(2))
                } }
            }
        }
    }

    @Test fun `create API through general worker HTTP commits classified item ready`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start()
            DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            val owner = UUID.randomUUID()
            val openAi = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            openAi.createContext("/v1/responses/input_tokens") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes = """{"input_tokens":1200}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            openAi.createContext("/v1/responses") { exchange ->
                exchange.requestBody.readAllBytes()
                val bytes = """{"status":"completed","usage":{"input_tokens":1200,"output_tokens":34},"output":[{"content":[{"type":"output_text","text":"{\"category_status\":\"ASSIGNED\",\"category_id\":\"C011\",\"purpose_status\":\"UNASSIGNED\",\"purpose_id\":null}"}]}]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            openAi.start()
            try {
                val catalog = TaxonomyCatalog.loadV1()
                val gateway = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"), baseUri = URI("http://127.0.0.1:${openAi.address.port}/v1"))
                val classifier = AiClassificationService(source, LlmBudgetService(source),
                    { catalog.snapshot(catalog.categories.map { it.id }.toSet()) },
                    gateway::classify)
                val processor = GeneralExtractionProcessor(source,
                    { url -> ExtractionResult.Complete(Metadata("CAYL cap", null, null, url)) }, classifier::classify)
                val worker = GeneralWorkerService(source, processor::process)

                testApplication {
                    application { routing {
                        wishlistRoutes(CreateWishlistItemService(source)) { owner }
                        workerRoutes(worker)
                    } }
                    val created = client.post("/v1/wishlist-items") {
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":"https://example.com/cayl-cap"}""")
                    }
                    assertEquals(HttpStatusCode.Created, created.status)
                    val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1, UUID::class.java) } }
                    val processed = client.post("/internal/worker/general") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jobId":"$jobId","generation":1}""")
                    }
                    assertEquals(HttpStatusCode.NoContent, processed.status)
                    source.connection.use { c -> c.createStatement().executeQuery("select analysis_status,predicted_category_id from wishlist_items").use { r ->
                        r.next()
                        assertEquals("READY", r.getString(1))
                        assertEquals("C011", r.getString(2))
                    } }
                }
            } finally { openAi.stop(0) }
        }
    }
}
