package app.http

import app.media.IntentRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRequestParserTest {
    private fun invalid(code: String, vararg fields: String) = MediaRequestParseResult.Invalid(code, fields.toSet())

    @Test fun `parses a strict intent body`() {
        assertEquals(MediaRequestParseResult.Valid(IntentRequest("image/jpeg", 812345, 2048, 1536)),
            parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":812345,"width":2048,"height":1536}"""))
    }

    @Test fun `rejects shape errors with fields`() {
        val ok = """"contentType":"image/jpeg","byteSize":10,"width":10,"height":10"""
        assertEquals(invalid("INVALID_MEDIA_REQUEST"), parseMediaIntentRequest("[]"))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "extra"), parseMediaIntentRequest("{$ok,\"extra\":1}"))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "height"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "byteSize"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":"10","width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "width"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10.5,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "contentType"), parseMediaIntentRequest("""{"contentType":1,"byteSize":10,"width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "byteSize"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":0,"width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "height"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10,"height":-${"9".repeat(40)}}"""))
    }

    @Test fun `value rules are left to the service and oversized integers saturate`() {
        assertEquals(MediaRequestParseResult.Valid(IntentRequest("image/png", 10, 10, 10)),
            parseMediaIntentRequest("""{"contentType":"image/png","byteSize":10,"width":10,"height":10}"""))
        val huge = "9".repeat(40)
        assertEquals(MediaRequestParseResult.Valid(IntentRequest("image/jpeg", Int.MAX_VALUE, Int.MAX_VALUE, 10)),
            parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":$huge,"width":$huge,"height":10}"""))
        assertEquals("MEDIA_TOO_LARGE", IntentRequest("image/jpeg", Int.MAX_VALUE, Int.MAX_VALUE, 10).violation())
        assertEquals("MEDIA_DIMENSIONS_EXCEEDED", IntentRequest("image/jpeg", 10, Int.MAX_VALUE, 10).violation())
    }
}
