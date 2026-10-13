package app.media

import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class LocalUploadResult { STORED, FORBIDDEN, EXISTS, TOO_LARGE }

/**
 * Local-only stand-in for the bucket. URLs carry an HMAC over method, key and expiry with a per-process key, and uploads
 * demand the same signed headers as GCS. A write lands in a temp file and is published with a hard link, which fails when
 * the target exists: completion never sees a partial file and only one of two concurrent PUTs wins.
 */
class LocalMediaStorage(
    private val root: Path,
    private val baseUrl: String,
    private val secret: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    private val clock: () -> Instant = Instant::now,
) : MediaStorage {
    override fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget =
        UploadTarget("PUT", url("PUT", objectKey, expiresAt), MediaKeys.uploadHeaders(contentType), expiresAt)

    override fun readUrl(objectKey: String, validFor: Duration): SignedRead {
        val expiresAt = clock().plus(validFor)
        return SignedRead(url("GET", objectKey, expiresAt), expiresAt)
    }

    override fun stat(objectKey: String): StoredObject? {
        val file = file(objectKey) ?: return null
        return if (Files.isRegularFile(file)) StoredObject(Files.size(file), MediaLimits.CONTENT_TYPE) else null
    }

    override fun read(objectKey: String, maxBytes: Int): ByteArray {
        val file = file(objectKey) ?: throw MediaStorageException()
        val bytes = Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) throw MediaObjectTooLarge()
        return bytes
    }

    override fun delete(objectKey: String) { file(objectKey)?.let(Files::deleteIfExists) }

    fun acceptUpload(objectKey: String, query: Map<String, String>, header: (String) -> String?, body: InputStream): LocalUploadResult {
        val target = file(objectKey) ?: return LocalUploadResult.FORBIDDEN
        if (!authorized("PUT", objectKey, query)) return LocalUploadResult.FORBIDDEN
        if (MediaKeys.uploadHeaders(MediaLimits.CONTENT_TYPE).any { (name, value) -> header(name) != value }) return LocalUploadResult.FORBIDDEN
        if (Files.exists(target)) return LocalUploadResult.EXISTS
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".upload-", ".part")
        try {
            val size = Files.newOutputStream(temp).use { out -> body.readNBytes(MediaLimits.MAX_BYTES + 1).also(out::write).size }
            if (size !in 1..MediaLimits.MAX_BYTES) return LocalUploadResult.TOO_LARGE
            return try { Files.createLink(target, temp); LocalUploadResult.STORED } catch (_: FileAlreadyExistsException) { LocalUploadResult.EXISTS }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** The file for a valid GET signature, or null. */
    fun openForRead(objectKey: String, query: Map<String, String>): Path? {
        val file = file(objectKey) ?: return null
        return if (authorized("GET", objectKey, query) && Files.isRegularFile(file)) file else null
    }

    private fun url(method: String, objectKey: String, expiresAt: Instant): String {
        val expires = expiresAt.epochSecond.toString()
        return "$baseUrl/local/media-objects/$objectKey?method=$method&expires=$expires&signature=" +
            URLEncoder.encode(signature(method, objectKey, expires), Charsets.UTF_8)
    }

    private fun authorized(method: String, objectKey: String, query: Map<String, String>): Boolean {
        val expires = query["expires"]?.toLongOrNull() ?: return false
        if (query["method"] != method || clock().epochSecond >= expires) return false
        val given = query["signature"] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), signature(method, objectKey, expires.toString()).toByteArray())
    }

    private fun signature(method: String, objectKey: String, expires: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$method\n$objectKey\n$expires".toByteArray()))
    }

    private fun file(objectKey: String): Path? = if (MediaKeys.isValid(objectKey)) root.resolve(objectKey) else null
}
