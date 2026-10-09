package app.http

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.testutil.PostgresTestContainer

class WishlistRoutesTest {
    @Test
    fun `uppercase https scheme preserves raw url through create replay and case conflict`() = app.testutil.withAnalysisDatabase { source ->
        val service = CreateWishlistItemService(source)
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(service, app.wishlist.GetWishlistItemService(source)) { owner } } }
            suspend fun submit(url: String) = client.post("/v1/wishlist-items") {
                header("Idempotency-Key", key.toString())
                contentType(ContentType.Application.Json)
                setBody("""{"sourceUrl":"$url"}""")
            }

            val created = submit("HTTPS://A.EXAMPLE/Path")
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals("HTTPS://A.EXAMPLE/Path", createdBody.getValue("sourceUrl").jsonPrimitive.content)

            val replay = submit("HTTPS://A.EXAMPLE/Path")
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotency-Replayed"])
            val replayBody = Json.parseToJsonElement(replay.bodyAsText()).jsonObject
            assertEquals(createdBody.getValue("id"), replayBody.getValue("id"))
            assertEquals("HTTPS://A.EXAMPLE/Path", replayBody.getValue("sourceUrl").jsonPrimitive.content)

            val conflict = submit("https://A.EXAMPLE/Path")
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            val error = Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals("IDEMPOTENCY_KEY_REUSED", error.getValue("code").jsonPrimitive.content)
            assertEquals(HttpStatusCode.UnprocessableEntity, submit("HTTPS://LOCALHOST/private").status)
        }
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from wishlist_items"))
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from analysis_jobs"))
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events"))
    }

    @Test fun `blocking detail lookup runs outside the route executor`() {
        app.testutil.assertBlockingRouteIo({ block -> wishlistRoutes(
            { _, _, _, _ -> app.wishlist.CreateResult.InvalidUrl },
            { _, _ -> block(); null },
        ) { UUID.randomUUID() } }, { get("/v1/wishlist-items/${UUID.randomUUID()}") })
    }

    @Test fun `detail lookup cancellation propagates to the request pipeline`() {
        app.testutil.assertRouteCancellation({ block -> wishlistRoutes(
            { _, _, _, _ -> app.wishlist.CreateResult.InvalidUrl },
            { _, _ -> block(); null },
        ) { UUID.randomUUID() } }, { get("/v1/wishlist-items/${UUID.randomUUID()}") })
    }

    @Test fun `blocking creation runs outside the route executor`() {
        app.testutil.assertBlockingRouteIo({ block -> wishlistRoutes({ _, _, _, _ -> block(); app.wishlist.CreateResult.InvalidUrl }, { _, _ -> null }) { UUID.randomUUID() } }, {
            post("/v1/wishlist-items") { header("Idempotency-Key", UUID.randomUUID().toString()); setBody("""{"sourceUrl":"https://example.com/item"}""") }
        })
    }

    @Test fun `creation cancellation propagates to the request pipeline`() {
        app.testutil.assertRouteCancellation({ block -> wishlistRoutes({ _, _, _, _ -> block(); app.wishlist.CreateResult.InvalidUrl }, { _, _ -> null }) { UUID.randomUUID() } }, {
            post("/v1/wishlist-items") { header("Idempotency-Key", UUID.randomUUID().toString()); setBody("""{"sourceUrl":"https://example.com/item"}""") }
        })
    }

    @Test
    fun `create replay conflict and invalid url use stable http contract`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val service = CreateWishlistItemService(source)
            val owner = UUID.randomUUID()
            val key = UUID.randomUUID()

            testApplication {
                application { installApiHttpSupport(); routing { wishlistRoutes(service, app.wishlist.GetWishlistItemService(source)) { owner } } }
                suspend fun submit(url: String) = client.post("/v1/wishlist-items") {
                    header("Idempotency-Key", key.toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"sourceUrl":"$url"}""")
                }

                val first = submit("https://example.com/item")
                assertEquals(HttpStatusCode.Created, first.status)
                assertTrue(first.headers[HttpHeaders.Location]?.startsWith("/v1/wishlist-items/") == true)
                assertTrue(first.bodyAsText().contains("\"sourceUrl\":\"https://example.com/item\""))
                assertTrue(first.bodyAsText().contains("\"clientSubmissionId\":\"$key\""))
                assertTrue(first.bodyAsText().contains("\"version\":1"))

                val replay = submit("https://example.com/item")
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals("true", replay.headers["Idempotency-Replayed"])

                val conflict = submit("https://example.com/another")
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                val error = Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("IDEMPOTENCY_KEY_REUSED", error.getValue("code").jsonPrimitive.content)
                assertEquals(conflict.headers["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
                UUID.fromString(first.headers["X-Request-ID"]!!)
                UUID.fromString(replay.headers["X-Request-ID"]!!)

                val invalid = submit("http://127.0.0.1/private")
                assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
            }
        }
    }
}
