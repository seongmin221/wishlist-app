package app.http

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaErrorMappingTest {
    @Test fun `every media error code has an explicit status and unknown codes have none`() {
        assertEquals(mapOf(
            "MEDIA_NOT_FOUND" to HttpStatusCode.NotFound,
            "IDEMPOTENCY_KEY_REUSED" to HttpStatusCode.Conflict, "MEDIA_OBJECT_MISSING" to HttpStatusCode.Conflict,
            "MEDIA_UPLOAD_EXPIRED" to HttpStatusCode.Gone,
            "INVALID_MEDIA_REQUEST" to HttpStatusCode.UnprocessableEntity, "UNSUPPORTED_MEDIA_TYPE" to HttpStatusCode.UnprocessableEntity,
            "MEDIA_TOO_LARGE" to HttpStatusCode.UnprocessableEntity, "MEDIA_DIMENSIONS_EXCEEDED" to HttpStatusCode.UnprocessableEntity,
            "MEDIA_INVALID_IMAGE" to HttpStatusCode.UnprocessableEntity,
            "MEDIA_UPLOAD_LIMIT_REACHED" to HttpStatusCode.TooManyRequests,
            "MEDIA_STORAGE_UNAVAILABLE" to HttpStatusCode.ServiceUnavailable, "MEDIA_BUSY" to HttpStatusCode.ServiceUnavailable,
        ), MEDIA_ERROR_CODES.associateWith { mediaErrorStatus(it) })
        assertNull(mediaErrorStatus("SOMETHING_NEW"))
    }
}
