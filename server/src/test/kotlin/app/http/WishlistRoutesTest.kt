package app.http

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import io.ktor.client.request.header
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
    @Test fun `blocking creation runs outside the route executor`() {
        app.testutil.assertBlockingRouteIo({ block -> wishlistRoutes({ _, _, _ -> block(); app.wishlist.CreateResult.InvalidUrl }) { UUID.randomUUID() } }, {
            post("/v1/wishlist-items") { header("Idempotency-Key", UUID.randomUUID().toString()); setBody("""{"sourceUrl":"https://example.com/item"}""") }
        })
    }

    @Test fun `creation cancellation propagates to the request pipeline`() {
        app.testutil.assertRouteCancellation({ block -> wishlistRoutes({ _, _, _ -> block(); app.wishlist.CreateResult.InvalidUrl }) { UUID.randomUUID() } }, {
            post("/v1/wishlist-items") { header("Idempotency-Key", UUID.randomUUID().toString()); setBody("""{"sourceUrl":"https://example.com/item"}""") }
        })
    }

    @Test
    fun `create replay conflict and invalid url use stable http contract`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val service = CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
            val owner = UUID.randomUUID()
            val key = UUID.randomUUID()

            testApplication {
                application { installApiHttpSupport(); routing { wishlistRoutes(service) { owner } } }
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
