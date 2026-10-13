package app.http

import app.home.HomeReadService
import app.purpose.PurposeService
import app.testutil.*
import app.wishlist.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MediaDisplayRoutesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.product() = this["product"]!!.jsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test fun `item home and purpose responses sign linked user images`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "desk", null)
        val time = Instant.parse("2026-10-12T10:00:00Z")
        val withMedia = ReadFixture(ReadPosition(time, UUID.randomUUID()),
            WishlistItemState(AnalysisStatus.READY, ReviewStatus.PENDING, LifecycleStatus.ACTIVE, "chair", "C026", null, null),
            purposeId = purpose, purposeSource = ValueSource.USER, imageUrl = "https://shop.example/chair.jpg")
        val external = withMedia.copy(position = ReadPosition(time.minusSeconds(1), UUID.randomUUID()), imageUrl = "https://shop.example/desk.jpg")
        source.connection.use { c -> insertReadFixtures(c, owner, listOf(withMedia, external)) }
        val (mediaId, key) = insertMedia(source, owner)
        analysisSql(source, "update wishlist_items set media_asset_id='$mediaId' where id='${withMedia.position.id}'")
        val submission = UUID.randomUUID()
        val created = CreateWishlistItemService(source).create(owner, submission, "https://shop.example/lamp").createdItemId
        val (processingMedia, processingKey) = insertMedia(source, owner)
        analysisSql(source, "update wishlist_items set media_asset_id='$processingMedia' where id='$created'")

        testApplication {
            application { installApiHttpSupport(); routing {
                wishlistRoutes(CreateWishlistItemService(source), GetWishlistItemService(source), storage.signer) { owner }
                wishlistReadRoutes(WishlistReadService(source), storage.signer) { owner }
                homeActionRoutes(WishlistReadService(source), storage.signer) { owner }
                homeSummaryRoutes(HomeReadService(source), storage.signer) { owner }
                purposeRoutes(PurposeService(source), storage.signer) { owner }
            } }
            fun assertSigned(product: JsonObject, expectedKey: String, expectedId: UUID) {
                assertTrue(product.text("imageUrl")!!.startsWith("memory://read/$expectedKey"), product.toString())
                assertEquals(expectedId.toString(), product.text("imageMediaId"))
            }
            val detail = json(client.get("/v1/wishlist-items/${withMedia.position.id}").bodyAsText()).product()
            assertSigned(detail, key, mediaId)
            val plain = json(client.get("/v1/wishlist-items/${external.position.id}").bodyAsText()).product()
            assertEquals("https://shop.example/desk.jpg", plain.text("imageUrl")); assertEquals(JsonNull, plain["imageMediaId"])

            val replay = client.post("/v1/wishlist-items") {
                header("Idempotency-Key", submission.toString()); setBody("""{"sourceUrl":"https://shop.example/lamp"}""")
            }
            assertEquals(HttpStatusCode.OK, replay.status)
            assertSigned(json(replay.bodyAsText()).product(), processingKey, processingMedia)

            val list = json(client.get("/v1/wishlist-items?categoryId=C026").bodyAsText())["items"]!!.jsonArray
                .map { it.jsonObject["item"]!!.jsonObject }
            assertSigned(list.first { it.text("id") == withMedia.position.id.toString() }.product(), key, mediaId)
            assertEquals("https://shop.example/desk.jpg", list.first { it.text("id") == external.position.id.toString() }.product().text("imageUrl"))

            val home = json(client.get("/v1/home").bodyAsText())
            val action = home["actionGroups"]!!.jsonArray.flatMap { it.jsonObject["previews"]!!.jsonArray }
                .map { it.jsonObject["item"]!!.jsonObject }.first { it.text("id") == created.toString() }
            assertSigned(action.product(), processingKey, processingMedia)
            val previews = home["recentPurposes"]!!.jsonArray.single().jsonObject["previews"]!!.jsonArray.map { it.jsonObject }
            val preview = previews.first { it.text("itemId") == withMedia.position.id.toString() }
            assertTrue(preview.text("imageUrl")!!.startsWith("memory://read/$key")); assertEquals(mediaId.toString(), preview.text("imageMediaId"))
            assertEquals("https://shop.example/desk.jpg", previews.first { it.text("itemId") == external.position.id.toString() }.text("imageUrl"))

            val actionItems = json(client.get("/v1/home/action-items?group=ANALYSIS_IN_PROGRESS").bodyAsText())["items"]!!.jsonArray
                .map { it.jsonObject["item"]!!.jsonObject }
            assertSigned(actionItems.first { it.text("id") == created.toString() }.product(), processingKey, processingMedia)

            val summary = json(client.get("/v1/purposes?projection=SUMMARY").bodyAsText())["purposes"]!!.jsonArray.single()
                .jsonObject["previews"]!!.jsonArray.map { it.jsonObject }.first { it.text("itemId") == withMedia.position.id.toString() }
            assertEquals(mediaId.toString(), summary.text("imageMediaId"))

            storage.failSign = true
            val unsigned = json(client.get("/v1/wishlist-items/${withMedia.position.id}").bodyAsText()).product()
            assertEquals(JsonNull, unsigned["imageUrl"]); assertEquals(mediaId.toString(), unsigned.text("imageMediaId"))
            assertEquals(HttpStatusCode.OK, client.get("/v1/wishlist-items?categoryId=C026").status)
            storage.failSign = false

            analysisSql(source, "update media_assets set status='DELETING' where id='$mediaId'")
            val fallback = json(client.get("/v1/wishlist-items/${withMedia.position.id}").bodyAsText()).product()
            assertEquals("https://shop.example/chair.jpg", fallback.text("imageUrl")); assertEquals(JsonNull, fallback["imageMediaId"])
        }
    }
}
