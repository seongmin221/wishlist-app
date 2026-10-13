package app

import app.analysis.PendingRecoveryReport
import app.budget.BudgetMaintenanceReport
import app.http.MediaWiring
import app.http.installApiHttpSupport
import app.http.localMediaRoutes
import app.maintenance.MaintenanceService
import app.media.*
import app.tasks.DispatchReport
import app.testutil.MediaFixtures
import app.testutil.analysisScalar
import app.testutil.analysisSql
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

/** Intent → direct PUT → completion → item display → release → maintenance cleanup, against PostgreSQL and the local store. */
class MediaUploadFlowTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun local(url: String) = URI(url).let { "${it.rawPath}?${it.rawQuery}" }

    @Test fun `uploaded image is shown on its item and cleaned up after release`() = withAnalysisDatabase { source ->
        val root = Files.createTempDirectory("media-flow")
        val storage = LocalMediaStorage(root, "http://localhost:8080")
        val signing = MediaSigningExecutor()
        val signer = CachingMediaUrlSigner(storage, signing)
        val wiring = MediaWiring(MediaService(source, storage, signer, MediaImageValidator(), signing), signer)
        val owner = UUID.randomUUID()
        val image = MediaFixtures.jpeg(640, 480)
        testApplication {
            application { installApiHttpSupport(); routing { apiRoutes(source, { _ -> }, wiring) { owner }; localMediaRoutes(storage) } }
            val item = json(client.post("/v1/wishlist-items") {
                header("Idempotency-Key", UUID.randomUUID().toString()); setBody("""{"sourceUrl":"https://shop.example/chair"}""")
            }.bodyAsText())["id"]!!.jsonPrimitive.content

            val intent = json(client.post("/v1/media/upload-intents") {
                header("Idempotency-Key", UUID.randomUUID().toString())
                setBody("""{"contentType":"image/jpeg","byteSize":${image.size},"width":640,"height":480}""")
            }.bodyAsText())
            val mediaId = intent["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val upload = intent["upload"]!!.jsonObject
            val put = client.put(local(upload["url"]!!.jsonPrimitive.content)) {
                upload["headers"]!!.jsonObject.forEach { (name, value) ->
                    if (name == HttpHeaders.ContentType) contentType(ContentType.parse(value.jsonPrimitive.content)) else header(name, value.jsonPrimitive.content)
                }
                setBody(image)
            }
            assertEquals(HttpStatusCode.OK, put.status)
            val completion = client.post("/v1/media/$mediaId/completion")
            assertEquals(HttpStatusCode.OK, completion.status)
            assertEquals("READY", json(completion.bodyAsText())["media"]!!.jsonObject["status"]!!.jsonPrimitive.content)

            // B7 links media through ITEM-04/07; until then the reference is a fixture.
            analysisSql(source, "update wishlist_items set media_asset_id='$mediaId',image_source='USER' where id='$item'")
            val product = json(client.get("/v1/wishlist-items/$item").bodyAsText())["product"]!!.jsonObject
            assertEquals(mediaId, product["imageMediaId"]!!.jsonPrimitive.content)
            val shown = client.get(local(product["imageUrl"]!!.jsonPrimitive.content))
            assertEquals(HttpStatusCode.OK, shown.status)
            assertContentEquals(image, shown.bodyAsBytes())
        }

        val maintenance = MaintenanceService({ _, _ -> DispatchReport(0, 0) }, { 0 }, { PendingRecoveryReport(0, 0, 0, 0, 0, 0) },
            { BudgetMaintenanceReport(0, 0) }, MediaCleanup(source, storage)::run)
        assertEquals(0, maintenance.runOnce().mediaDeleted)
        val mediaId = analysisScalar(source, "select media_asset_id from wishlist_items where media_asset_id is not null")!!
        analysisSql(source, "update wishlist_items set media_asset_id=null where media_asset_id='$mediaId'")
        analysisSql(source, "update media_assets set released_at=clock_timestamp()-interval '25 hours' where id='$mediaId'")
        val report = maintenance.runOnce()
        assertEquals(1, report.mediaDeleted)
        assertEquals(emptyList(), report.failedSteps)
        assertEquals("DELETED", analysisScalar(source, "select status from media_assets where id='$mediaId'"))
        assertNull(storage.stat(analysisScalar(source, "select object_key from media_assets where id='$mediaId'")!!))
        signing.close()
    }
}
