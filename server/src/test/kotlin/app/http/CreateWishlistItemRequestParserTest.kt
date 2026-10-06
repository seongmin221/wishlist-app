package app.http

import java.time.Instant
import kotlin.test.*

class CreateWishlistItemRequestParserTest {
    @Test fun `optional time accepts omitted and null values`() {
        for (body in listOf("""{"sourceUrl":"https://example.com/item"}""", """{"sourceUrl":"https://example.com/item","clientCreatedAt":null}""")) {
            val request = assertIs<CreateRequestParseResult.Valid>(parseCreateRequest(body))
            assertEquals("https://example.com/item", request.sourceUrl)
            assertNull(request.clientCreatedAt)
        }
    }

    @Test fun `offset time becomes an instant without narrowing the historical range`() {
        for ((input, expected) in listOf("2026-10-06T19:00:00+09:00" to "2026-10-06T10:00:00Z", "1500-01-02T10:00:00Z" to "1500-01-02T10:00:00Z")) {
            val request = assertIs<CreateRequestParseResult.Valid>(parseCreateRequest("""{"sourceUrl":"https://example.com/item","clientCreatedAt":"$input"}"""))
            assertEquals(Instant.parse(expected), request.clientCreatedAt)
        }
    }

    @Test fun `invalid wire shapes retain the URL error contract`() {
        for (body in listOf("{", "[]", "{}", """{"sourceUrl":null}""", """{"sourceUrl":42}""", """{"sourceUrl":{}}""")) {
            assertEquals(CreateRequestParseResult.Invalid(CreateRequestError.INVALID_URL), parseCreateRequest(body))
        }
    }

    @Test fun `sharing time validates both local and UTC year and rejects non strings`() {
        for (time in listOf("\"9999-12-31T23:59:00-18:00\"", "\"0001-01-01T00:00:00+18:00\"", "\"0000-01-01T00:00:00Z\"",
            "\"+10000-01-01T00:00:00Z\"", "\"2026-02-30T10:00:00Z\"", "\"2026-10-06T10:00:00\"", "\"bad\"", "42", "true", "{}")) {
            assertEquals(CreateRequestParseResult.Invalid(CreateRequestError.INVALID_CLIENT_CREATED_AT),
                parseCreateRequest("""{"sourceUrl":"https://example.com/item","clientCreatedAt":$time}"""), time)
        }
    }

    @Test fun `database precision normalization cannot round the final allowed instant into year 10000`() {
        val request = assertIs<CreateRequestParseResult.Valid>(parseCreateRequest(
            """{"sourceUrl":"https://example.com/item","clientCreatedAt":"9999-12-31T23:59:59.999999999Z"}""",
        ))
        assertEquals(Instant.parse("9999-12-31T23:59:59.999999Z"), request.clientCreatedAt)
    }
}
