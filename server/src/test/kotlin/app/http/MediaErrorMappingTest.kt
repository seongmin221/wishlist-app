package app.http

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaErrorMappingTest {
    @Test fun `known codes map to their status and unknown codes have none`() {
        assertEquals(HttpStatusCode.Conflict, MEDIA_ERROR_STATUS["MEDIA_OBJECT_MISSING"])
        assertEquals(HttpStatusCode.ServiceUnavailable, MEDIA_ERROR_STATUS["MEDIA_BUSY"])
        assertNull(MEDIA_ERROR_STATUS["SOMETHING_NEW"])
    }
}
