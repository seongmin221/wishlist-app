package app.media

import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(MediaService::class.java)

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

    /**
     * Verifies the uploaded object and settles the row. Storage reads and decoding run outside transactions; when a
     * lookup finds no object or fails, the row is re-read first because a concurrent completion may already have settled
     * it (a FAILED peer deletes the object).
     */
    fun complete(owner: UUID, id: UUID): MediaView {
        val asset = load(owner, id)
        if (asset.status != MediaStatus.PENDING || asset.expired) return view(asset)
        val stored = try {
            storage.stat(asset.objectKey)
        } catch (_: MediaStorageException) {
            return settledOr(owner, id, "MEDIA_STORAGE_UNAVAILABLE")
        } ?: return settledOr(owner, id, "MEDIA_OBJECT_MISSING")
        // Reads and decodes have separate permits: a slow bucket cannot hold decode permits, and concurrent reads cannot
        // hold unbounded bytes. After precheck the stat size equals the declared size, so it bounds the read buffer.
        val verdict = validator.precheck(stored, asset.declared)?.let(ImageVerdict::Invalid) ?: run {
            val bytes = try {
                validator.readGated { storage.read(asset.objectKey, stored.byteSize.toInt()) }
            } catch (_: MediaObjectTooLarge) {
                return@run ImageVerdict.Invalid(MediaFailureReason.SIZE_MISMATCH)
            } catch (_: MediaStorageException) {
                return settledOr(owner, id, "MEDIA_STORAGE_UNAVAILABLE")
            }
            validator.gated { validator.verify(bytes, asset.declared) }
        }
        beforeApply(id)
        var failedNow = false
        val settled = source.inTransaction { c ->
            val locked = media.find(c, owner, id, lock = true) ?: throw MediaException("MEDIA_NOT_FOUND")
            if (locked.status != MediaStatus.PENDING) return@inTransaction locked
            // Expiry is judged at apply time with the DB clock, so the outcome near the deadline does not depend on how
            // long verification took.
            if (locked.expired) return@inTransaction locked
            when (verdict) {
                is ImageVerdict.Valid -> media.markReady(c, id, verdict)
                is ImageVerdict.Invalid -> { media.markFailed(c, id, verdict.reason); failedNow = true }
            }
            checkNotNull(media.find(c, owner, id))
        }
        if (failedNow) try {
            storage.delete(asset.objectKey)
        } catch (_: MediaStorageException) {
            log.warn("Failed media object delete deferred to cleanup mediaId={}", id)
        }
        return view(settled)
    }

    private fun load(owner: UUID, id: UUID): MediaAsset =
        source.inTransaction { c -> media.find(c, owner, id) } ?: throw MediaException("MEDIA_NOT_FOUND")

    /** The settled row's response when a peer finished first, otherwise [code]. */
    private fun settledOr(owner: UUID, id: UUID, code: String): MediaView {
        val current = load(owner, id)
        if (current.status != MediaStatus.PENDING) return view(current)
        throw MediaException(code)
    }

    /** Response for a stored row: an upload target while PENDING, a display URL once READY, an error otherwise. */
    private fun view(asset: MediaAsset): MediaView = when (asset.status) {
        MediaStatus.READY -> MediaView(asset, null, signer.sign(asset.objectKey))
        MediaStatus.FAILED -> throw MediaException("MEDIA_INVALID_IMAGE", reason = asset.failureReason)
        MediaStatus.DELETING, MediaStatus.DELETED -> throw MediaException("MEDIA_UPLOAD_EXPIRED")
        MediaStatus.PENDING -> {
            if (asset.expired) throw MediaException("MEDIA_UPLOAD_EXPIRED")
            val upload = signing.await {
                try {
                    storage.uploadTarget(asset.objectKey, asset.contentType, asset.uploadExpiresAt)
                } catch (error: Exception) {
                    log.warn("Media upload signing failed mediaId={} exceptionType={}", asset.id, error.javaClass.name)
                    throw error
                }
            } ?: throw MediaException("MEDIA_STORAGE_UNAVAILABLE")
            MediaView(asset, upload, null)
        }
    }
}
