package app.http

import app.category.CategoryService
import app.testutil.withAnalysisDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class CategoryRoutesTest {
    @Test fun `category routes enforce authentication keys input scope and owner contracts`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        testApplication {
            application { installApiHttpSupport(); routing { categoryRoutes(CategoryService(source)) { call ->
                when(call.request.headers["Test-Owner"]) { "owner" -> owner; "other" -> other; else -> null }
            } } }
            assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/categories?scope=SELECT").status)
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/categories") { header("Test-Owner","owner") }.status)
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/categories?scope=SELECT&scope=BROWSE") { header("Test-Owner","owner") }.status)
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/categories?scope=SELECT&parentId=C026") { header("Test-Owner","owner") }.status)
            val select = client.get("/v1/categories?scope=SELECT") { header("Test-Owner","owner") }
            assertEquals(11,json(select.bodyAsText())["groups"]!!.jsonArray.size)
            val key = UUID.randomUUID()
            val body = """{"parentId":"G003","name":"Desk","description":"desc","examples":["one"]}"""
            assertEquals(HttpStatusCode.BadRequest,client.post("/v1/custom-categories") { header("Test-Owner","owner"); setBody(body) }.status)
            val created = client.post("/v1/custom-categories") { header("Test-Owner","owner"); header("Idempotency-Key",key); setBody(body) }
            assertEquals(HttpStatusCode.Created,created.status)
            val detail = json(created.bodyAsText())["category"]!!.jsonObject
            val id = detail["id"]!!.jsonPrimitive.content
            assertEquals("/v1/custom-categories/$id",created.headers[HttpHeaders.Location])
            assertEquals(setOf("id","name","parentId","description","examples","itemCount","version"),detail.keys)
            val replay = client.post("/v1/custom-categories") { header("Test-Owner","owner"); header("Idempotency-Key",key); setBody(body) }
            assertEquals(HttpStatusCode.OK,replay.status); assertEquals("true",replay.headers["Idempotency-Replayed"])
            assertEquals(HttpStatusCode.NotFound,client.get("/v1/custom-categories/$id") { header("Test-Owner","other") }.status)
            assertEquals(HttpStatusCode.UnprocessableEntity,client.patch("/v1/custom-categories/$id") { header("Test-Owner","owner"); setBody("""{"expectedVersion":1,"parentId":"G003"}""") }.status)
            val edit = client.patch("/v1/custom-categories/$id") { header("Test-Owner","owner"); setBody("""{"expectedVersion":1,"name":"Renamed","description":null,"examples":null}""") }
            assertEquals(HttpStatusCode.OK,edit.status)
            val edited = json(edit.bodyAsText()); assertEquals(2,edited["version"]!!.jsonPrimitive.int); assertEquals(JsonNull,edited["description"])
            assertEquals(HttpStatusCode.Conflict,client.patch("/v1/custom-categories/$id") { header("Test-Owner","owner"); setBody("""{"expectedVersion":1,"name":"Old"}""") }.status)
            val conflict = client.post("/v1/custom-categories") { header("Test-Owner","owner"); header("Idempotency-Key",key); setBody(body.replace("Desk","Other")) }
            assertEquals(HttpStatusCode.Conflict,conflict.status)
            assertEquals(conflict.headers["X-Request-ID"],json(conflict.bodyAsText())["error"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
            repeat(4) { n -> client.post("/v1/custom-categories") { header("Test-Owner","owner"); header("Idempotency-Key",UUID.randomUUID()); setBody(body.replace("Desk","New-$n")) } }
            val limited = client.post("/v1/custom-categories") { header("Test-Owner","owner"); header("Idempotency-Key",UUID.randomUUID()); setBody(body.replace("Desk","Sixth")) }
            assertEquals(HttpStatusCode.TooManyRequests,limited.status)
            assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
        }
    }
    private fun json(raw:String)=Json.parseToJsonElement(raw).jsonObject
}
