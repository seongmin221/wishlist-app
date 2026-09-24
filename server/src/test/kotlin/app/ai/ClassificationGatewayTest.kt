package app.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassificationGatewayTest {
    private val candidates = CandidateSnapshot(setOf("CAT_HOME"), setOf("PUR_GIFT"))

    @Test fun `unknown purpose id is unusable`() {
        val result = ClassificationSchema.validate("""{"category_status":"ASSIGNED","category_id":"CAT_HOME","purpose_status":"ASSIGNED","purpose_id":"PUR_UNKNOWN"}""", candidates)
        assertIs<ClassificationResult.Unusable>(result)
    }

    @Test fun `valid assigned ids are accepted`() {
        val result = ClassificationSchema.validate("""{"category_status":"ASSIGNED","category_id":"CAT_HOME","purpose_status":"ASSIGNED","purpose_id":"PUR_GIFT"}""", candidates)
        assertEquals(ClassificationResult.Assigned("CAT_HOME", "PUR_GIFT"), result)
    }

    @Test fun `abstention has no ids`() {
        val result = ClassificationSchema.validate("""{"category_status":"ABSTAINED","category_id":null,"purpose_status":"UNASSIGNED","purpose_id":null}""", candidates)
        assertEquals(ClassificationResult.Abstained, result)
    }

    @Test fun `alias is not accepted as production snapshot`() {
        assertFailsWith<IllegalArgumentException> { OpenAiConfig("gpt-5.6-luna", "secret") }
        OpenAiConfig("gpt-5.6-luna", "secret", allowLocalAlias = true)
    }

    @Test fun `request fixes token limits and store false`() {
        val request = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"))
            .requestBody("Lamp", candidates)
        assertEquals(80, request["max_output_tokens"]?.toString()?.toInt())
        assertEquals("false", request["store"].toString())
        assertTrue(request["input"].toString().contains("CAT_HOME"))
    }

    @Test fun `full taxonomy is compact enough for the personal classification path`() {
        val catalog = TaxonomyCatalog.loadV1()
        val snapshot = catalog.snapshot(catalog.categories.map { it.id }.toSet())
        val request = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"))
            .requestBody("CAYL cap", snapshot)
        val input = request["input"].toString()
        assertTrue(input.contains("C011:"))
        assertTrue(input.length < 1500, "prompt should use compact category labels")
    }

    @Test fun `token preflight rejects oversized request without calling responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var responses = 0
        server.createContext("/v1/responses/input_tokens") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            if (body.keys != setOf("model", "input", "text")) {
                exchange.sendResponseHeaders(400, -1)
                return@createContext
            }
            val bytes = """{"input_tokens":2001}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { exchange ->
            responses++
            exchange.sendResponseHeaders(500, -1)
        }
        server.start()
        try {
            val gateway = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"), baseUri = URI("http://127.0.0.1:${server.address.port}/v1"))
            assertEquals(ClassificationResult.Unusable("input_too_large"), gateway.classify("Lamp", candidates).classification)
            assertEquals(0, responses)
        } finally { server.stop(0) }
    }

    @Test fun `token preflight allows classification and preserves response usage`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/responses/input_tokens") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = """{"input_tokens":1200}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = """{"status":"completed","usage":{"input_tokens":1200,"output_tokens":34},"output":[{"content":[{"type":"output_text","text":"{\"category_status\":\"ASSIGNED\",\"category_id\":\"CAT_HOME\",\"purpose_status\":\"UNASSIGNED\",\"purpose_id\":null}"}]}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val gateway = OpenAiResponsesGateway(OpenAiConfig("gpt-5.6-luna-2026-09-01", "secret"), baseUri = URI("http://127.0.0.1:${server.address.port}/v1"))
            val result = gateway.classify("Lamp", candidates)
            assertEquals(ClassificationResult.Assigned("CAT_HOME", null), result.classification)
            assertEquals(1200, result.inputTokens)
        } finally { server.stop(0) }
    }
}
