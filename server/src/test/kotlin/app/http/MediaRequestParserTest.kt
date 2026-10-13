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

    @Test fun `rejects shape errors with fields and value errors in priority order`() {
        val ok = """"contentType":"image/jpeg","byteSize":10,"width":10,"height":10"""
        assertEquals(invalid("INVALID_MEDIA_REQUEST"), parseMediaIntentRequest("[]"))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "extra"), parseMediaIntentRequest("{$ok,\"extra\":1}"))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "height"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "byteSize"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":"10","width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "width"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10.5,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "contentType"), parseMediaIntentRequest("""{"contentType":1,"byteSize":10,"width":10,"height":10}"""))
        assertEquals(invalid("MEDIA_TOO_LARGE"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":99999999999,"width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "byteSize"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":1${"0".repeat(19)},"width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "byteSize"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":0,"width":10,"height":10}"""))
        assertEquals(invalid("INVALID_MEDIA_REQUEST", "height"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":10,"height":-1}"""))
        assertEquals(invalid("UNSUPPORTED_MEDIA_TYPE"), parseMediaIntentRequest("""{"contentType":"image/png","byteSize":10,"width":10,"height":10}"""))
        assertEquals(invalid("MEDIA_TOO_LARGE"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":5242881,"width":10,"height":10}"""))
        assertEquals(invalid("MEDIA_DIMENSIONS_EXCEEDED"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":10,"width":2049,"height":10}"""))
        assertEquals(invalid("UNSUPPORTED_MEDIA_TYPE"), parseMediaIntentRequest("""{"contentType":"image/png","byteSize":5242881,"width":2049,"height":10}"""))
        assertEquals(invalid("MEDIA_TOO_LARGE"), parseMediaIntentRequest("""{"contentType":"image/jpeg","byteSize":5242881,"width":2049,"height":10}"""))
    }
}
