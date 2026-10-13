package app.media

import java.time.Duration

object MediaLimits {
    const val CONTENT_TYPE = "image/jpeg"
    const val MAX_BYTES = 5_242_880
    const val MAX_EDGE = 2048
    const val MAX_SCANS = 64
    const val PENDING_LIMIT = 10
    val UPLOAD_VALIDITY: Duration = Duration.ofMinutes(15)
    val READ_VALIDITY: Duration = Duration.ofHours(1)
    val READ_REUSE_MINIMUM: Duration = Duration.ofMinutes(45)
    val CLEANUP_GRACE: Duration = Duration.ofHours(24)
    val DELETE_LEASE: Duration = Duration.ofMinutes(2)
    /** Signed PUT header value; the lower bound matches the minimum declared size. */
    const val CONTENT_LENGTH_RANGE = "1,$MAX_BYTES"
}

enum class MediaStatus { PENDING, READY, FAILED, DELETING, DELETED }

enum class MediaFailureReason {
    SIZE_MISMATCH, CONTENT_TYPE_MISMATCH, NOT_JPEG, UNSUPPORTED_JPEG, METADATA_PRESENT,
    DIMENSIONS_EXCEEDED, DIMENSIONS_MISMATCH, DECODE_FAILED,
}

/** Size and content type the storage reports for an uploaded object. */
data class StoredObject(val byteSize: Long, val contentType: String?)

class MediaException(val code: String, val reason: MediaFailureReason? = null, val retryAfterSeconds: Int? = null) : RuntimeException(code)
