package app.http

import app.DatabaseFactory
import app.wishlist.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class ApiErrorTest {
    @Test fun `unexpected error logs request id type and frames without sensitive message`() = testApplication {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = java.lang.reflect.Proxy.newProxyInstance(org.slf4j.Logger::class.java.classLoader, arrayOf(org.slf4j.Logger::class.java)) { _, method, args ->
            when {
                method.name == "getName" -> "api-error-test"
                method.name.startsWith("is") -> true
                method.name == "error" -> { messages.add(args.orEmpty().joinToString { if (it is Array<*>) it.contentToString() else it.toString() }); null }
                else -> null
            }
        } as org.slf4j.Logger
        environment { log = logger }
        application {
            installApiHttpSupport()
            routing { get("/boom") { error("credential-and-query-must-not-leak") } }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val diagnostic = messages.joinToString()
        assertTrue(diagnostic.contains(response.headers["X-Request-ID"]!!))
        assertTrue(diagnostic.contains("IllegalStateException"))
        assertTrue(diagnostic.contains("ApiErrorTest"))
        assertFalse(diagnostic.contains("credential-and-query-must-not-leak"))
    }

    @Test fun `Ktor request errors stay client errors with safe envelope`() = testApplication {
        application {
            installApiHttpSupport()
            routing {
                get("/bad") { throw io.ktor.server.plugins.BadRequestException("sensitive request") }
                get("/missing") { throw io.ktor.server.plugins.NotFoundException("sensitive path") }
            }
        }
        for ((path, status, code) in listOf(Triple("/bad", HttpStatusCode.BadRequest, "BAD_REQUEST"), Triple("/missing", HttpStatusCode.NotFound, "NOT_FOUND"))) {
            val response = client.get(path)
            assertEquals(status, response.status)
            val envelope = ApiJson.decodeFromString<ApiErrorEnvelope>(response.bodyAsText())
            assertEquals(code, envelope.error.code)
            assertEquals(response.headers["X-Request-ID"], envelope.error.requestId)
            assertFalse(response.bodyAsText().contains("sensitive"))
        }
    }

    @Test fun `missing authentication keeps unauthorized code and trace`() =
        assertCreateError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "{}", authenticated = false)

    @Test fun `malformed key keeps bad request code and trace`() =
        assertCreateError(HttpStatusCode.BadRequest, "INVALID_IDEMPOTENCY_KEY", "{}", key = "bad")

    @Test fun `malformed and missing URL retain unprocessable status`() {
        for (body in listOf("{", "{}", "{\"sourceUrl\":null}", "{\"sourceUrl\":{}}")) {
            assertCreateError(HttpStatusCode.UnprocessableEntity, "INVALID_URL", body)
        }
    }

    @Test fun `private URL is rejected before database connection`() =
        assertCreateError(HttpStatusCode.UnprocessableEntity, "INVALID_URL", "{\"sourceUrl\":\"http://127.0.0.1/private\"}")

    @Test fun `server generates unique request ids and ignores supplied trace`() = testApplication {
        application {
            installApiHttpSupport()
            routing { get("/ok") { call.respondText("ok") } }
        }
        val first = client.get("/ok") { header("X-Request-ID", "user-controlled") }
        val second = client.get("/ok")
        val id = first.headers["X-Request-ID"]!!
        UUID.fromString(id)
        assertNotEquals("user-controlled", id)
        assertNotEquals(id, second.headers["X-Request-ID"])
        assertEquals(HttpStatusCode.OK, first.status)
    }

    @Test fun `unexpected exception returns safe internal error`() = testApplication {
        application {
            installApiHttpSupport()
            routing { get("/boom") { error("credential-and-query-must-not-leak") } }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("credential-and-query-must-not-leak"))
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        assertEquals("INTERNAL_ERROR", error.getValue("code").jsonPrimitive.content)
        assertEquals(response.headers["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), error.getValue("details"))
    }

    @Test fun `cancellation escapes the API error mapping`() = testApplication {
        application {
            installApiHttpSupport()
            routing { get("/cancel") { throw CancellationException("cancelled work") } }
        }
        // Ktor's test engine maps an escaped cancellation to its own diagnostic response.
        // The API handler must not replace it with the INTERNAL_ERROR envelope.
        val response = client.get("/cancel")
        assertTrue(response.bodyAsText().contains("CancellationException: cancelled work"))
        assertFalse(response.bodyAsText().contains("INTERNAL_ERROR"))
    }

    @Test fun `safe error details preserve numeric versions`() = testApplication {
        application {
            installApiHttpSupport()
            routing {
                get("/conflict") {
                    call.respondApiError(HttpStatusCode.Conflict, "WISHLIST_ITEM_STATE_CONFLICT",
                        mapOf("currentVersion" to JsonPrimitive(8)))
                }
            }
        }
        val response = client.get("/conflict")
        val envelope = ApiJson.decodeFromString<ApiErrorEnvelope>(response.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(JsonPrimitive(8), envelope.error.details.getValue("currentVersion"))
        assertEquals(response.headers["X-Request-ID"], envelope.error.requestId)
    }

    @Test fun `item DTO roundtrips wire enums and explicit null fields`() {
        val item = WishlistItemDto(
            id = UUID.randomUUID().toString(), clientSubmissionId = UUID.randomUUID().toString(), version = 7,
            sourceUrl = "https://example.com/item", product = ProductDto(name = "제품명"),
            category = CategoryDto(id = "C026", source = ValueSource.AI), purpose = PurposeDto(),
            analysis = AnalysisDto(status = AnalysisStatus.PARTIAL), reviewStatus = ReviewStatus.CONFIRMED,
            lifecycleStatus = LifecycleStatus.ACTIVE, requiredAction = RequiredAction.NONE,
            createdAt = "2026-10-05T00:00:00Z", updatedAt = "2026-10-05T00:00:01Z",
            allowedActions = setOf(ItemAction.EDIT, ItemAction.DELETE),
        )
        val encoded = ApiJson.encodeToString(item)
        assertEquals(item, ApiJson.decodeFromString<WishlistItemDto>(encoded))
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonNull, json.getValue("manualCompletionAt"))
        assertEquals(JsonNull, json.getValue("product").jsonObject.getValue("price"))
        assertEquals(JsonNull, json.getValue("product").jsonObject.getValue("imageUrl"))
        assertEquals(JsonNull, json.getValue("category").jsonObject.getValue("missingReason"))
        assertEquals(JsonNull, json.getValue("analysis").jsonObject.getValue("failureCode"))
        assertEquals("UNASSIGNED", json.getValue("purpose").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("PARTIAL", json.getValue("analysis").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(setOf("EDIT", "DELETE"), json.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun `price JSON number is preserved without floating point conversion`() {
        val product = ProductDto(price = BigDecimal("123456789012345.67"))
        val encoded = ApiJson.encodeToString(product)
        val decoded = ApiJson.decodeFromString<ProductDto>(encoded)
        assertEquals(product, decoded)
        val price = Json.parseToJsonElement(encoded).jsonObject.getValue("price").jsonPrimitive
        assertEquals("123456789012345.67", price.content)
        assertFalse(price.isString)
    }

    private fun assertCreateError(
        status: HttpStatusCode, code: String, body: String,
        authenticated: Boolean = true, key: String = UUID.randomUUID().toString(),
    ) = testApplication {
        val source = DatabaseFactory.dataSource("jdbc:postgresql://127.0.0.1:1/not_used", "test", "test")
        application {
            installApiHttpSupport()
            routing { wishlistRoutes(CreateWishlistItemService(source)) { if (authenticated) UUID.randomUUID() else null } }
        }
        val response = client.post("/v1/wishlist-items") {
            header("Idempotency-Key", key)
            header("X-Request-ID", "do-not-trust-this")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(status, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(code, error.getValue("code").jsonPrimitive.content)
        val id = error.getValue("requestId").jsonPrimitive.content
        UUID.fromString(id)
        assertEquals(id, response.headers["X-Request-ID"])
        assertNotEquals("do-not-trust-this", id)
    }
}
