package app.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class PurposeGatewaySizingTest {
    data class Seen(val product: Int, val custom: Int, val customDetail: Boolean, val purposes: Int, val description: Boolean, val items: Boolean)

    private val catalog = TaxonomyCatalog.loadV1()
    private fun purposes(n: Int) = (1..n).map { PurposeCandidate(UUID.randomUUID().toString(), "목적$it ],;:\"", "설명$it", listOf("상품$it-a", "상품$it-b")) }
    private fun snapshot(custom: Int, purposes: List<PurposeCandidate>, version: Int = 3): CandidateSnapshot {
        val public = catalog.snapshot(catalog.categories.map { it.id }.toSet())
        val customRows = (0 until custom).associate { UUID.randomUUID().toString() to CustomCategoryCandidate(1, "책상$it", "G003", "설명", listOf("예시")) }
        return public.copy(categoryIds = public.categoryIds + customRows.keys, customCategories = customRows, ownerId = UUID.randomUUID().toString(),
            purposeIds = purposes.map { it.id }.toCollection(LinkedHashSet()), schemaVersion = version, purposeCandidates = purposes)
    }

    private fun run(snapshot: CandidateSnapshot, accept: (Seen) -> Boolean, answer: (JsonObject) -> String): Triple<List<Seen>, GatewayResponse, List<JsonObject>> {
        val seen = mutableListOf<Seen>(); val paid = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun data(request: JsonObject) = Json.parseToJsonElement(request.getValue("input").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        server.createContext("/v1/responses/input_tokens") { exchange ->
            val data = data(Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject)
            val custom = data.getValue("custom_categories").jsonArray; val sent = data.getValue("purposes").jsonArray
            val s = Seen(data.getValue("product").jsonPrimitive.content.length, custom.size, custom.any { "description" in it.jsonObject },
                sent.size, sent.any { "d" in it.jsonObject }, sent.any { "i" in it.jsonObject })
            seen.add(s)
            val bytes = """{"input_tokens":${if (accept(s)) 2500 else 2501}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { exchange ->
            val data = data(Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject).also(paid::add)
            val text = answer(data).replace("\"", "\\\"")
            val bytes = """{"status":"completed","usage":{"input_tokens":2400,"output_tokens":30},"output":[{"content":[{"type":"output_text","text":"$text"}]}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val result = OpenAiResponsesGateway(OpenAiConfig("test-snapshot", "secret"), baseUri = URI("http://127.0.0.1:${server.address.port}/v1"))
                .classify("상".repeat(2400), snapshot) {}
            return Triple(seen, result, paid)
        } finally { server.stop(0) }
    }
    private fun answer(purpose: String?) = """{"category_status":"ASSIGNED","category_id":"C026","purpose_status":"${if (purpose == null) "UNASSIGNED" else "ASSIGNED"}","purpose_id":${purpose?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun `tiers shrink purpose evidence before custom evidence for every input shape`() {
        val all = listOf(Seen(2400,20,true,10,true,true), Seen(2400,20,true,10,true,false), Seen(2400,20,true,10,false,false),
            Seen(800,20,false,10,false,false), Seen(160,20,false,10,false,false), Seen(160,0,false,10,false,false),
            Seen(160,0,false,5,false,false), Seen(160,0,false,0,false,false))
        val (both, tooLarge, paid) = run(snapshot(20, purposes(10)), { false }, { error("must not pay") })
        assertEquals(all, both); assertTrue(paid.isEmpty())
        assertEquals(ClassificationResult.Unusable("input_too_large"), tooLarge.classification)
        assertEquals(listOf(all[0], all[1], all[2], all[5], all[6], all[7]).map { it.copy(custom = 0, customDetail = false) },
            run(snapshot(0, purposes(10)), { false }, { error("") }).first)
        assertEquals(listOf(20, 20, 20, 0), run(snapshot(20, emptyList()), { false }, { error("") }).first.map { it.custom })
        assertEquals(listOf(2400, 160), run(snapshot(0, emptyList()), { false }, { error("") }).first.map { it.product })
    }

    @Test fun `aliases follow snapshot order and map back to judged purpose ids`() {
        val purposes = purposes(10)
        val (_, response, paid) = run(snapshot(3, purposes), { !it.items }, { answer("P02") })
        assertEquals(ClassificationResult.Assigned("C026", purposes[1].id, purposeJudged = true), response.classification)
        assertEquals(SentCandidates(1, 3, 10), response.sent)
        val sent = paid.single().getValue("purposes").jsonArray.map { it.jsonObject }
        assertEquals((1..10).map { "P%02d".format(it) }, sent.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(purposes.map { it.name }, sent.map { it.getValue("n").jsonPrimitive.content })
        assertFalse(paid.single().toString().contains(purposes[0].id))
    }

    @Test fun `unassigned is a judgement only when every purpose was sent`() {
        val purposes = purposes(10)
        assertEquals(true, (run(snapshot(0, purposes), { it.purposes == 10 && !it.description }, { answer(null) }).second.classification as ClassificationResult.Assigned).purposeJudged)
        assertEquals(false, (run(snapshot(0, purposes), { it.purposes == 5 }, { answer(null) }).second.classification as ClassificationResult.Assigned).purposeJudged)
        assertIs<ClassificationResult.Unusable>(run(snapshot(0, purposes), { it.purposes == 5 }, { answer("P07") }).second.classification)
        assertIs<ClassificationResult.Unusable>(run(snapshot(0, purposes), { true }, { answer(purposes[0].id) }).second.classification)
    }

    @Test fun `legacy snapshots send no purposes and never judge them`() {
        val legacy = snapshot(0, emptyList(), version = 2).copy(purposeIds = setOf("PUR_GIFT"), purposeLabels = mapOf("PUR_GIFT" to "gift"))
        val (seen, response, _) = run(legacy, { true }, { answer(null) })
        assertEquals(0, seen.single().purposes)
        assertEquals(ClassificationResult.Assigned("C026", null, purposeJudged = false), response.classification)
    }
}
