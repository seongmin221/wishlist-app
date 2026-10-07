package app.http

import app.purpose.PurposeChanges
import app.purpose.PurposeService
import app.testutil.analysisScalar
import app.testutil.insertPurpose
import app.testutil.analysisSql
import app.testutil.createdItemId
import app.testutil.withAnalysisDatabase
import app.wishlist.CreateWishlistItemService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

class WishlistDetailRoutesTest {
    @Test fun `detail authentication and malformed id errors happen before database access`() = testApplication {
        val source = app.DatabaseFactory.dataSource("jdbc:postgresql://127.0.0.1:1/not_used", "test", "test")
        application {
            installApiHttpSupport()
            routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { call ->
                if (call.request.headers["Test-Authenticated"] == "true") UUID.randomUUID() else null
            } }
        }
        val unauthorized = client.get("/v1/wishlist-items/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals("UNAUTHORIZED", Json.parseToJsonElement(unauthorized.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        for (id in listOf("invalid-id", "1-1-1-1-1")) {
            val response = client.get("/v1/wishlist-items/$id") { header("Test-Authenticated", "true") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals("INVALID_WISHLIST_ITEM_ID", error.getValue("code").jsonPrimitive.content)
            assertEquals(response.headers["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
        }
    }

    @Test fun `partial failed and manually completed details preserve actions and safe failure codes`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").createdItemId
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { owner } } }
            val cases = listOf(
                Triple("PARTIAL", "AI_ABSTAINED", "AI_ABSTAINED"),
                Triple("PARTIAL", "AI_UNUSABLE_RESPONSE", "AI_UNUSABLE_RESPONSE"),
                Triple("FAILED_RETRYABLE", null, "ANALYSIS_RETRYABLE_FAILURE"),
                Triple("FAILED_TERMINAL", "private-ip-and-credential", "ANALYSIS_FAILED"),
                Triple("FAILED_TERMINAL", "ACCESS_DENIED", "ACCESS_DENIED"),
            )
            for ((status, failure, publicCode) in cases) {
                analysisSql(source, """update wishlist_items set analysis_status='$status',
                    analysis_failure_code=${failure?.let { "'$it'" } ?: "null"} where id='$item'""")
                val response = client.get("/v1/wishlist-items/$item")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(publicCode, body.getValue("analysis").jsonObject.getValue("failureCode").jsonPrimitive.content)
                val actions = body.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(if (status == "FAILED_RETRYABLE") setOf("EDIT", "DELETE", "MANUAL_COMPLETE", "REANALYZE")
                    else setOf("EDIT", "DELETE", "MANUAL_COMPLETE"), actions)
                assertEquals("INFORMATION_COMPLETION", body.getValue("requiredAction").jsonPrimitive.content)
                assertFalse(response.bodyAsText().contains("private-ip-and-credential"))
            }
            analysisSql(source, """update wishlist_items set analysis_status='READY', product_name='보완', name_source='USER',
                category_id='C026', category_source='USER', category_missing_reason=null, review_status='CONFIRMED',
                manual_completion_at='2026-10-06T01:00:00Z' where id='$item'""")
            val body = Json.parseToJsonElement(client.get("/v1/wishlist-items/$item").bodyAsText()).jsonObject
            assertEquals("2026-10-06T01:00:00Z", body.getValue("manualCompletionAt").jsonPrimitive.content)
            assertEquals("CONFIRMED", body.getValue("reviewStatus").jsonPrimitive.content)
            assertEquals("NONE", body.getValue("requiredAction").jsonPrimitive.content)
            assertEquals(setOf("EDIT", "DELETE"), body.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(JsonNull, body.getValue("analysis").jsonObject.getValue("failureCode"))
        }
    }

    @Test fun `detail and replay expose current metadata state sources and actions`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        val url = "https://example.com/item"
        val item = CreateWishlistItemService(source).create(owner, key, url).createdItemId
        analysisSql(source, """update wishlist_items set product_name='상품', product_image_url='https://example.com/image',
            name_source='USER', image_source='AI', category_id='C026', category_source='AI', category_missing_reason=null,
            analysis_status='READY', review_status='PENDING', version=7 where id='$item'""")
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { owner } } }
            val detail = client.get("/v1/wishlist-items/$item")
            assertEquals(HttpStatusCode.OK, detail.status)
            val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals("상품", body.getValue("product").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("https://example.com/image", body.getValue("product").jsonObject.getValue("imageUrl").jsonPrimitive.content)
            assertEquals("USER", body.getValue("product").jsonObject.getValue("nameSource").jsonPrimitive.content)
            assertEquals("AI", body.getValue("product").jsonObject.getValue("imageSource").jsonPrimitive.content)
            assertEquals("C026", body.getValue("category").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("READY", body.getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals("PENDING", body.getValue("reviewStatus").jsonPrimitive.content)
            assertEquals("CLASSIFICATION_REVIEW", body.getValue("requiredAction").jsonPrimitive.content)
            assertEquals(setOf("EDIT", "DELETE", "REVIEW"), body.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(7, body.getValue("version").jsonPrimitive.int)
            val replay = client.post("/v1/wishlist-items") {
                header("Idempotency-Key", key.toString()); setBody("""{"sourceUrl":"$url"}""")
            }
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(body, Json.parseToJsonElement(replay.bodyAsText()))
        }
    }

    @Test fun `detail hides other owners missing and deleted items while replay retains tombstone`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        val item = CreateWishlistItemService(source).create(owner, key, "https://example.com/item").createdItemId
        testApplication {
            application {
                installApiHttpSupport()
                routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { call ->
                    if (call.request.headers["Test-Owner"] == "other") UUID.randomUUID() else owner
                } }
            }
            for (response in listOf(client.get("/v1/wishlist-items/$item") { header("Test-Owner", "other") },
                client.get("/v1/wishlist-items/${UUID.randomUUID()}"))) {
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                assertEquals("WISHLIST_ITEM_NOT_FOUND", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            analysisSql(source, "update wishlist_items set lifecycle_status='ARCHIVED' where id='$item'")
            val archived = client.get("/v1/wishlist-items/${item.toString().uppercase()}")
            assertEquals(HttpStatusCode.OK, archived.status)
            val archivedBody = Json.parseToJsonElement(archived.bodyAsText()).jsonObject
            assertEquals(item.toString(), archivedBody.getValue("id").jsonPrimitive.content)
            assertEquals("ARCHIVED", archivedBody.getValue("lifecycleStatus").jsonPrimitive.content)
            assertEquals("NONE", archivedBody.getValue("requiredAction").jsonPrimitive.content)
            assertEquals(JsonArray(emptyList()), archivedBody.getValue("allowedActions"))
            analysisSql(source, "update wishlist_items set lifecycle_status='DELETED' where id='$item'")
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/wishlist-items/$item").status)
            val replay = client.post("/v1/wishlist-items") {
                header("Idempotency-Key", key.toString()); setBody("""{"sourceUrl":"https://example.com/item"}""")
            }
            assertEquals(HttpStatusCode.OK, replay.status)
            val body = Json.parseToJsonElement(replay.bodyAsText()).jsonObject
            assertEquals(item.toString(), body.getValue("id").jsonPrimitive.content)
            assertEquals("DELETED", body.getValue("lifecycleStatus").jsonPrimitive.content)
            assertEquals("NONE", body.getValue("requiredAction").jsonPrimitive.content)
            assertEquals(JsonArray(emptyList()), body.getValue("allowedActions"))
            assertEquals("1", analysisScalar(source, "select count(*) from wishlist_items"))
            assertEquals("1", analysisScalar(source, "select count(*) from analysis_jobs"))
            assertEquals("1", analysisScalar(source, "select count(*) from outbox_events"))
        }
    }

    @Test fun `client time is stored separately normalized and never overwritten on replay`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { owner } } }
            suspend fun submit(time: String?) = client.post("/v1/wishlist-items") {
                header("Idempotency-Key", key.toString())
                setBody("""{"sourceUrl":"https://example.com/item","clientCreatedAt":${time?.let { JsonPrimitive(it) } ?: JsonNull}}""")
            }
            val created = submit("2026-09-13T19:00:00+09:00")
            assertEquals(HttpStatusCode.Created, created.status)
            val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertTrue("clientCreatedAt" in body, "creation must return the persisted client timestamp")
            assertEquals("2026-09-13T10:00:00Z", body.getValue("clientCreatedAt").jsonPrimitive.content)
            assertNotEquals(body.getValue("createdAt"), body.getValue("clientCreatedAt"))
            for (time in listOf(null, "2026-09-14T10:00:00Z")) {
                val replay = submit(time)
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals(body, Json.parseToJsonElement(replay.bodyAsText()))
            }
            assertEquals(body, Json.parseToJsonElement(client.get(created.headers[HttpHeaders.Location]!!).bodyAsText()))
        }
    }

    @Test fun `initially absent sharing time stays null when replay supplies one`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { owner } } }
            for (initialTime in listOf("", ",\"clientCreatedAt\":null")) {
                val key = UUID.randomUUID().toString().uppercase()
                val created = client.post("/v1/wishlist-items") {
                    header("Idempotency-Key", key); setBody("""{"sourceUrl":"https://example.com/item"$initialTime}""")
                }
                assertEquals(HttpStatusCode.Created, created.status)
                val original = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals(JsonNull, original.getValue("clientCreatedAt"))
                val replay = client.post("/v1/wishlist-items") {
                    header("Idempotency-Key", key.lowercase())
                    setBody("""{"sourceUrl":"https://example.com/item","clientCreatedAt":"2026-10-06T00:00:00Z"}""")
                }
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals(original, Json.parseToJsonElement(replay.bodyAsText()))
            }
        }
    }

    @Test fun `invalid client time is rejected before writes with safe traced error`() = withAnalysisDatabase { source ->
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { UUID.randomUUID() } } }
            for (time in listOf("\"2026-09-13T10:00:00\"", "\"bad-secret\"", "42", "{}", "true", "\"\"",
                "\"9999-12-31T23:59:00-18:00\"", "\"0001-01-01T00:00:00+18:00\"",
                "\"0000-01-01T00:00:00Z\"", "\"+10000-01-01T00:00:00Z\"")) {
                val response = client.post("/v1/wishlist-items") {
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    setBody("""{"sourceUrl":"https://example.com/item","clientCreatedAt":$time}""")
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, time)
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("INVALID_CLIENT_CREATED_AT", error.getValue("code").jsonPrimitive.content)
                assertEquals(response.headers["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
                assertFalse(response.bodyAsText().contains("bad-secret"))
            }
            assertEquals("0", analysisScalar(source, "select count(*) from wishlist_items"))
        }
    }

    @Test fun `item detail shows current purpose display values and empty purpose shapes`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val linked = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/a").createdItemId
        val empty = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/b").createdItemId
        val cleared = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/c").createdItemId
        val purpose = insertPurpose(source, owner, "gift", null)
        analysisSql(source, "update wishlist_items set purpose_id='$purpose',purpose_source='AI' where id='$linked'")
        analysisSql(source, "update wishlist_items set purpose_source='USER' where id='$cleared'")
        testApplication {
            application { installApiHttpSupport(); routing { wishlistRoutes(CreateWishlistItemService(source), app.wishlist.GetWishlistItemService(source)) { owner } } }
            suspend fun item(id: UUID) = Json.parseToJsonElement(client.get("/v1/wishlist-items/$id").bodyAsText()).jsonObject
            val before = item(linked)
            assertEquals(buildJsonObject { put("id", purpose.toString()); put("name", "gift"); put("colorKey", "CORAL"); put("iconKey", "HEART"); put("source", "AI") },
                before.getValue("purpose"))
            PurposeService(source).patch(owner, purpose, 1, PurposeChanges(name = "renamed"))
            val after = item(linked)
            assertEquals("renamed", after.getValue("purpose").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(before.getValue("version"), after.getValue("version"))
            for ((id, expected) in listOf(empty to "UNASSIGNED", cleared to "USER")) assertEquals(buildJsonObject {
                put("id", JsonNull); put("name", JsonNull); put("colorKey", JsonNull); put("iconKey", JsonNull); put("source", expected)
            }, item(id).getValue("purpose"))
        }
    }
}
