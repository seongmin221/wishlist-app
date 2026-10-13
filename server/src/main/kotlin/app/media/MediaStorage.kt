package app.media

import java.time.Duration
import java.time.Instant
import java.util.UUID

data class UploadTarget(val method: String, val url: String, val headers: Map<String, String>, val expiresAt: Instant)
data class SignedRead(val url: String, val expiresAt: Instant)

/** Signs a display URL; null when signing failed or timed out, so callers show a placeholder. */
fun interface MediaUrlSigner {
    fun sign(objectKey: String): SignedRead?
}

/**
 * Object store for user images. Calls run outside DB transactions. Signing may be a remote call (IAM signBlob), so every
 * signing path goes through [MediaSigningExecutor].
 */
interface MediaStorage {
    fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget
    fun readUrl(objectKey: String, validFor: Duration): SignedRead
    /** Null when the object does not exist. */
    fun stat(objectKey: String): StoredObject?
    /** Throws [MediaObjectTooLarge] past [maxBytes]. */
    fun read(objectKey: String, maxBytes: Int): ByteArray
    /** Succeeds when the object is already gone. */
    fun delete(objectKey: String)
}

class MediaStorageException(cause: Throwable? = null) : RuntimeException("Media storage unavailable", cause)
class MediaObjectTooLarge : RuntimeException("Media object exceeds the read limit")

object MediaKeys {
    private val pattern = Regex("media/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.jpg")

    fun objectKey(owner: UUID, media: UUID) = "media/$owner/$media.jpg"
    fun isValid(objectKey: String) = pattern.matches(objectKey)

    /** Headers the client must send unchanged; each one is part of the upload signature. */
    fun uploadHeaders(contentType: String) = linkedMapOf(
        "Content-Type" to contentType, "x-goog-if-generation-match" to "0", "x-goog-content-length-range" to MediaLimits.CONTENT_LENGTH_RANGE,
    )
}
