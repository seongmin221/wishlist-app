package app.http

import app.purpose.PurposeService
import app.testutil.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class PurposeRoutesTest {
    private val createBody = """{"name":"출퇴근 헤드폰","description":"지하철","colorKey":"CORAL","iconKey":"MUSIC"}"""

    @Test fun `purpose routes enforce auth keys input owner version and query contracts`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        testApplication {
            application { installApiHttpSupport(); routing { purposeRoutes(PurposeService(source)) { call ->
                when (call.request.headers["Test-Owner"]) { "owner" -> owner; "other" -> other; else -> null }
            } } }
            fun HttpRequestBuilder.me() = header("Test-Owner", "owner")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/purposes?projection=SELECT").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/purposes") { me(); setBody(createBody) }.status)
            val key = UUID.randomUUID()
            val created = client.post("/v1/purposes") { me(); header("Idempotency-Key", key); setBody(createBody) }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = json(created.bodyAsText())
            val purpose = createdJson["purpose"]!!.jsonObject; val id = purpose["id"]!!.jsonPrimitive.content
            assertEquals("/v1/purposes/$id", created.headers[HttpHeaders.Location])
            assertEquals(setOf("purpose", "activeCount", "purposeLimit"), createdJson.keys)
            assertEquals(30, createdJson["purposeLimit"]!!.jsonPrimitive.int)
            assertEquals(setOf("id","name","description","colorKey","iconKey","candidateCount","membershipVersion","version","activity","createdAt","updatedAt","allowedActions"), purpose.keys)
            assertEquals(listOf("EDIT","DELETE","ADD_CANDIDATES"), purpose["allowedActions"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("CREATED", purpose["activity"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            val replay = client.post("/v1/purposes") { me(); header("Idempotency-Key", key); setBody(createBody) }
            assertEquals(HttpStatusCode.OK, replay.status); assertEquals("true", replay.headers["Idempotency-Replayed"])
            val reused = client.post("/v1/purposes") { me(); header("Idempotency-Key", key); setBody(createBody.replace("CORAL", "MINT")) }
            assertEquals(HttpStatusCode.Conflict, reused.status)
            assertEquals("IDEMPOTENCY_KEY_REUSED", json(reused.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val invalid = client.post("/v1/purposes") { me(); header("Idempotency-Key", UUID.randomUUID()); setBody(createBody.replace("CORAL", "RED")) }
            assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
            assertEquals(listOf("colorKey"), json(invalid.bodyAsText())["error"]!!.jsonObject["details"]!!.jsonObject["fields"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/purposes/not-a-uuid") { me() }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/purposes/$id") { header("Test-Owner", "other") }.status)
            val patched = client.patch("/v1/purposes/$id") { me(); setBody("""{"expectedVersion":1,"description":null,"iconKey":"BOOK"}""") }
            assertEquals(HttpStatusCode.OK, patched.status)
            assertEquals(JsonNull, json(patched.bodyAsText())["description"]); assertEquals(2, json(patched.bodyAsText())["version"]!!.jsonPrimitive.int)
            val stale = client.patch("/v1/purposes/$id") { me(); setBody("""{"expectedVersion":1,"name":"old"}""") }
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals(2, json(stale.bodyAsText())["error"]!!.jsonObject["details"]!!.jsonObject["currentVersion"]!!.jsonPrimitive.int)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.patch("/v1/purposes/$id") { me(); setBody("""{"expectedVersion":2,"name":null}""") }.status)
            assertEquals(HttpStatusCode.NotFound, client.patch("/v1/purposes/$id") { header("Test-Owner", "other"); setBody("""{"expectedVersion":2,"name":"x"}""") }.status)
        }
    }

    @Test fun `list projections cursors and limits`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID(); val service = PurposeService(source)
        repeat(3) { insertPurpose(source, owner, "p$it", "d$it") }
        testApplication {
            application { installApiHttpSupport(); routing { purposeRoutes(service) { call ->
                when (call.request.headers["Test-Owner"]) { "owner" -> owner; "other" -> other; else -> null } } } }
            fun HttpRequestBuilder.me() = header("Test-Owner", "owner")
            for (query in listOf("", "?projection=ALL", "?projection=SELECT&projection=SUMMARY", "?projection=SELECT&limit=0",
                "?projection=SELECT&limit=31", "?projection=SELECT&limit=x", "?projection=SELECT&cursor=***", "?projection=SELECT&cursor=bm90LWEtY3Vyc29y"))
                assertEquals(HttpStatusCode.BadRequest, client.get("/v1/purposes$query") { me() }.status, query)
            val select = json(client.get("/v1/purposes?projection=SELECT&limit=2") { me() }.bodyAsText())
            assertEquals(setOf("projection","purposes","nextCursor","activeCount","purposeLimit","archiveSummary"), select.keys)
            assertEquals(setOf("id","name","colorKey","iconKey","version"), select["purposes"]!!.jsonArray.first().jsonObject.keys)
            assertEquals(buildJsonObject { put("count", 0); putJsonArray("recentTitles") {} }, select["archiveSummary"])
            val cursor = select["nextCursor"]!!.jsonPrimitive.content
            assertEquals(1, json(client.get("/v1/purposes?projection=SELECT&cursor=$cursor") { me() }.bodyAsText())["purposes"]!!.jsonArray.size)
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/purposes?projection=SUMMARY&cursor=$cursor") { me() }.status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/purposes?projection=SELECT&cursor=$cursor") { header("Test-Owner", "other") }.status)
            val summary = json(client.get("/v1/purposes?projection=SUMMARY") { me() }.bodyAsText())
            assertEquals(setOf("id","name","colorKey","iconKey","version","description","candidateCount","activity","previews"),
                summary["purposes"]!!.jsonArray.first().jsonObject.keys)
            assertEquals(JsonNull, summary["nextCursor"])
        }
    }

    @Test fun `owner matched cursor with an out of range timestamp is rejected as a cursor error`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(3) { insertPurpose(source, owner, "p$it", null) }
        testApplication {
            application { installApiHttpSupport(); routing { purposeRoutes(PurposeService(source)) { owner } } }
            val cursor = json(client.get("/v1/purposes?projection=SELECT&limit=2").bodyAsText())["nextCursor"]!!.jsonPrimitive.content
            val parts = String(java.util.Base64.getUrlDecoder().decode(cursor)).split("|").toMutableList()
            for (micros in listOf("-9000000000000000000", "9223372036854775808")) {
                parts[3] = micros
                val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())
                val response = client.get("/v1/purposes?projection=SELECT&cursor=$forged")
                assertEquals(HttpStatusCode.BadRequest, response.status, micros)
                assertEquals("INVALID_PURPOSE_CURSOR", json(response.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            }
            // A valid position hint within PostgreSQL's range is allowed, even beyond year 9999.
            parts[3]="9000000000000000000"
            val valid=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())
            assertEquals(HttpStatusCode.OK,client.get("/v1/purposes?projection=SELECT&cursor=$valid").status)

        }
    }

    @Test fun `limit and rate errors carry codes and retry header`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        testApplication {
            application { installApiHttpSupport(); routing { purposeRoutes(service) { owner } } }
            repeat(10) { n -> client.post("/v1/purposes") { header("Idempotency-Key", UUID.randomUUID()); setBody(createBody.replace("출퇴근", "n$n")) } }
            val limited = client.post("/v1/purposes") { header("Idempotency-Key", UUID.randomUUID()); setBody(createBody) }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
            assertEquals(limited.headers["X-Request-ID"], json(limited.bodyAsText())["error"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
            analysisSql(source, "update mutation_receipts set created_at=clock_timestamp()-interval '61 seconds'")
            repeat(20) { insertPurpose(source, owner, "seed-$it", null) }
            val full = client.post("/v1/purposes") { header("Idempotency-Key", UUID.randomUUID()); setBody(createBody) }
            assertEquals(HttpStatusCode.Conflict, full.status)
            assertEquals("PURPOSE_LIMIT_REACHED", json(full.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
