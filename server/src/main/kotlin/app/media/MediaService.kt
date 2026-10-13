package app.media

import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class IntentRequest(val contentType: String, val byteSize: Int, val width: Int, val height: Int) {
    /** First violated rule, in the public priority order, or null. */
    fun violation(): String? = when {
        byteSize <= 0 || width <= 0 || height <= 0 -> "INVALID_MEDIA_REQUEST"
        contentType != MediaLimits.CONTENT_TYPE -> "UNSUPPORTED_MEDIA_TYPE"
        byteSize > MediaLimits.MAX_BYTES -> "MEDIA_TOO_LARGE"
        width > MediaLimits.MAX_EDGE || height > MediaLimits.MAX_EDGE -> "MEDIA_DIMENSIONS_EXCEEDED"
        else -> null
    }

    fun fingerprint(): String = MutationReceipts.fingerprint(JsonObject(linkedMapOf(
        "contentType" to JsonPrimitive(contentType), "byteSize" to JsonPrimitive(byteSize),
        "width" to JsonPrimitive(width), "height" to JsonPrimitive(height),
    )))
}

data class MediaView(val asset: MediaAsset, val upload: UploadTarget?, val display: SignedRead?)
data class MediaIntentResult(val view: MediaView, val replayed: Boolean)

/**
 * MEDIA-01 and MEDIA-02. Transactions stay short and storage calls run outside them. Intents serialize on the owner lock;
 * completion locks only the media row.
 */
class MediaService(
    private val source: DataSource,
    private val storage: MediaStorage,
    private val signer: MediaUrlSigner,
    private val validator: MediaImageValidator,
    private val signing: MediaSigningExecutor,
    /** Test hook between verification and the final row update. */
    private val beforeApply: (UUID) -> Unit = {},
) {
    private val media = MediaRepository()

    fun createIntent(owner: UUID, key: UUID, request: IntentRequest): MediaIntentResult {
        request.violation()?.let { throw MediaException(it) }
        val fingerprint = request.fingerprint()
        val (asset, replayed) = source.inTransaction { c ->
            OwnerStructureLock.lock(c, owner)
            val existing = media.findByKey(c, owner, key)
            if (existing != null) {
                if (existing.fingerprint != fingerprint) throw MediaException("IDEMPOTENCY_KEY_REUSED")
                return@inTransaction existing.asset to true
            }
            val (live, retryAfter) = media.livePending(c, owner)
            if (live >= MediaLimits.PENDING_LIMIT) throw MediaException("MEDIA_UPLOAD_LIMIT_REACHED", retryAfterSeconds = retryAfter)
            media.insert(c, owner, key, fingerprint, request) to false
        }
        return MediaIntentResult(view(asset), replayed)
    }

    /** Response for a stored row: an upload target while PENDING, a display URL once READY, an error otherwise. */
    private fun view(asset: MediaAsset): MediaView = when (asset.status) {
        MediaStatus.READY -> MediaView(asset, null, signer.sign(asset.objectKey))
        MediaStatus.FAILED -> throw MediaException("MEDIA_INVALID_IMAGE", reason = asset.failureReason)
        MediaStatus.DELETING, MediaStatus.DELETED -> throw MediaException("MEDIA_UPLOAD_EXPIRED")
        MediaStatus.PENDING -> {
            if (asset.expired) throw MediaException("MEDIA_UPLOAD_EXPIRED")
            val upload = signing.await { storage.uploadTarget(asset.objectKey, asset.contentType, asset.uploadExpiresAt) }
                ?: throw MediaException("MEDIA_STORAGE_UNAVAILABLE")
            MediaView(asset, upload, null)
        }
    }
}
