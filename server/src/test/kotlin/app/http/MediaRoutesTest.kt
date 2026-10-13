package app.http

import app.testutil.InMemoryMediaStorage
import app.testutil.MediaFixtures
import app.testutil.analysisSql
import app.testutil.mediaWiring
import app.testutil.withAnalysisDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

class MediaRoutesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun body(size: Int, width: Int = 64, height: Int = 48) = """{"contentType":"image/jpeg","byteSize":$size,"width":$width,"height":$height}"""

    @Test fun `intent and completion routes map the contract`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        val image = MediaFixtures.jpeg(64, 48)
        testApplication {
            application { installApiHttpSupport(); routing { mediaRoutes(mediaWiring(source, storage)) { call ->
                when (call.request.headers["Test-Owner"]) { "owner" -> owner; "other" -> other; else -> null }
            } } }
            suspend fun intent(key: UUID, payload: String = body(image.size), who: String = "owner") = client.post("/v1/media/upload-intents") {
                header("Test-Owner", who); header("Idempotency-Key", key.toString()); contentType(ContentType.Application.Json); setBody(payload)
            }
            suspend fun complete(id: String, who: String = "owner") = client.post("/v1/media/$id/completion") { header("Test-Owner", who) }
            fun code(text: String) = json(text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/media/upload-intents") { setBody(body(10)) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/media/upload-intents") { header("Test-Owner", "owner"); setBody(body(10)) }.status)
            val invalid = intent(UUID.randomUUID(), body(10, width = 2049))
            assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status); assertEquals("MEDIA_DIMENSIONS_EXCEEDED", code(invalid.bodyAsText()))
            val missing = intent(UUID.randomUUID(), """{"contentType":"image/jpeg"}""")
            assertEquals("INVALID_MEDIA_REQUEST", code(missing.bodyAsText()))
            assertEquals(setOf("byteSize", "width", "height"), json(missing.bodyAsText())["error"]!!.jsonObject["details"]!!.jsonObject["fields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())

            val key = UUID.randomUUID()
            val created = intent(key)
            assertEquals(HttpStatusCode.Created, created.status)
            assertNull(created.headers[HttpHeaders.Location])
            val payload = json(created.bodyAsText())
            assertEquals(setOf("media", "upload", "display", "limits"), payload.keys)
            assertEquals(JsonNull, payload["display"])
            val media = payload["media"]!!.jsonObject
            assertEquals(setOf("id", "status", "contentType", "byteSize", "width", "height", "expiresAt"), media.keys)
            assertEquals("PENDING", media["status"]!!.jsonPrimitive.content)
            val upload = payload["upload"]!!.jsonObject
            assertEquals("PUT", upload["method"]!!.jsonPrimitive.content)
            assertEquals(mapOf("Content-Type" to "image/jpeg", "x-goog-if-generation-match" to "0", "x-goog-content-length-range" to "1,5242880"),
                upload["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertEquals(json("""{"contentTypes":["image/jpeg"],"maxBytes":5242880,"maxLongEdge":2048}"""), payload["limits"])
            val id = media["id"]!!.jsonPrimitive.content
            val replay = intent(key)
            assertEquals(HttpStatusCode.OK, replay.status); assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(HttpStatusCode.Conflict, intent(key, body(image.size + 1)).status)

            assertEquals(HttpStatusCode.BadRequest, complete("not-a-uuid").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/media/$id/completion").status)
            assertEquals(HttpStatusCode.NotFound, complete(id, "other").status)
            val notUploaded = complete(id)
            assertEquals(HttpStatusCode.Conflict, notUploaded.status); assertEquals("MEDIA_OBJECT_MISSING", code(notUploaded.bodyAsText()))
            storage.failStat = true
            assertEquals(HttpStatusCode.ServiceUnavailable, complete(id).status)
            storage.failStat = false
            storage.put("media/$owner/$id.jpg", image)
            val ready = complete(id)
            assertEquals(HttpStatusCode.OK, ready.status)
            val readyBody = json(ready.bodyAsText())
            assertEquals("READY", readyBody["media"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, readyBody["upload"])
            assertEquals(setOf("url", "expiresAt"), readyBody["display"]!!.jsonObject.keys)
            assertEquals(HttpStatusCode.OK, complete(id).status)

            val badKey = UUID.randomUUID()
            val bad = json(intent(badKey, body(MediaFixtures.exif(image).size)).bodyAsText())["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
            storage.put("media/$owner/$bad.jpg", MediaFixtures.exif(image))
            val failed = complete(bad)
            assertEquals(HttpStatusCode.UnprocessableEntity, failed.status)
            assertEquals("MEDIA_INVALID_IMAGE", code(failed.bodyAsText()))
            assertEquals("METADATA_PRESENT", json(failed.bodyAsText())["error"]!!.jsonObject["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.UnprocessableEntity, intent(badKey, body(MediaFixtures.exif(image).size)).status)

            val expiredKey = UUID.randomUUID()
            val expired = json(intent(expiredKey).bodyAsText())["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
            analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()-interval '1 second' where id='$expired'")
            assertEquals(HttpStatusCode.Gone, complete(expired).status)
            assertEquals(HttpStatusCode.Gone, intent(expiredKey).status)

            repeat(10) { intent(UUID.randomUUID()) }
            val limited = intent(UUID.randomUUID())
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter]?.toIntOrNull())
            storage.failSign = true
            val unavailable = intent(UUID.randomUUID(), who = "other")
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status); assertEquals("MEDIA_STORAGE_UNAVAILABLE", code(unavailable.bodyAsText()))
        }
    }
}
