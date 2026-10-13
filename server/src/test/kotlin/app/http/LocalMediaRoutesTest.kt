package app.http

import app.media.LocalMediaStorage
import app.testutil.MediaFixtures
import app.testutil.mediaWiring
import app.testutil.withAnalysisDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class LocalMediaRoutesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun local(url: String) = URI(url).let { "${it.rawPath}?${it.rawQuery}" }

    @Test fun `local storage round trip through signed urls`() = withAnalysisDatabase { source ->
        val storage = LocalMediaStorage(Files.createTempDirectory("local-media-routes"), "http://localhost:8080")
        val owner = UUID.randomUUID()
        val image = MediaFixtures.jpeg(64, 48)
        testApplication {
            application { installApiHttpSupport(); routing { mediaRoutes(mediaWiring(source, storage)) { owner }; localMediaRoutes(storage) } }
            val intent = json(client.post("/v1/media/upload-intents") {
                header("Idempotency-Key", UUID.randomUUID().toString())
                setBody("""{"contentType":"image/jpeg","byteSize":${image.size},"width":64,"height":48}""")
            }.bodyAsText())
            val id = intent["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val upload = intent["upload"]!!.jsonObject
            val url = local(upload["url"]!!.jsonPrimitive.content)
            val headers = upload["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            suspend fun put(bytes: ByteArray, sent: Map<String, String> = headers, target: String = url) = client.put(target) {
                sent.forEach { (name, value) -> if (name != HttpHeaders.ContentType) header(name, value) }
                sent[HttpHeaders.ContentType]?.let { contentType(ContentType.parse(it)) }
                setBody(bytes)
            }.status

            assertEquals(HttpStatusCode.Forbidden, put(image, headers - "x-goog-if-generation-match"))
            assertEquals(HttpStatusCode.Forbidden, put(image, headers + ("x-goog-content-length-range" to "1,9")))
            assertEquals(HttpStatusCode.Forbidden, put(image, target = url.replace("signature=", "signature=x")))
            assertEquals(HttpStatusCode.PayloadTooLarge, put(ByteArray(5_242_881)))
            assertEquals(HttpStatusCode.BadRequest, put(ByteArray(0)))
            assertEquals(HttpStatusCode.OK, put(image))
            assertEquals(HttpStatusCode.PreconditionFailed, put(MediaFixtures.jpeg(10, 10)))

            val ready = json(client.post("/v1/media/$id/completion").bodyAsText())
            assertEquals("READY", ready["media"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            val display = local(ready["display"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            val read = client.get(display)
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(ContentType.Image.JPEG, read.contentType())
            assertContentEquals(image, read.bodyAsBytes())
            assertEquals(HttpStatusCode.Forbidden, client.get(url).status)  // the upload signature cannot read
            assertEquals(HttpStatusCode.Forbidden, client.get(display.replace("signature=", "signature=x")).status)
            storage.delete("media/$owner/$id.jpg")
            assertEquals(HttpStatusCode.NotFound, client.get(display).status)
        }
    }
}
