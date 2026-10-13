package app.media

import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class LocalUploadResult { STORED, FORBIDDEN, EXISTS, TOO_LARGE, EMPTY }
sealed interface LocalRead {
    data object Forbidden : LocalRead
    data object Missing : LocalRead
    class Found(val bytes: ByteArray) : LocalRead
}

/**
 * Local-only stand-in for the bucket. URLs carry an HMAC over method, key and expiry with a per-process key, and uploads
 * demand the same signed headers as GCS. A write lands in a temp file and is published with a hard link, which fails when
 * the target exists: completion never sees a partial file and only one of two concurrent PUTs wins.
 */
class LocalMediaStorage(
    private val root: Path,
    private val baseUrl: String,
    /** Signing key; by default kept in [root] so URLs survive a restart (hot reload) of the local server. */
    secret: ByteArray? = null,
    private val clock: () -> Instant = Instant::now,
    private val publish: (Path, Path) -> Unit = { target, temp -> Files.createLink(target, temp) },
) : MediaStorage {
    private val secret: ByteArray by lazy { secret ?: io { loadOrCreateKey(root.resolve(".signing-key")) } }

    override fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget =
        UploadTarget("PUT", url("PUT", objectKey, expiresAt), MediaKeys.uploadHeaders(contentType), expiresAt)

    override fun readUrl(objectKey: String, validFor: Duration): SignedRead {
        val expiresAt = clock().plus(validFor)
        return SignedRead(url("GET", objectKey, expiresAt), expiresAt)
    }

    override fun stat(objectKey: String): StoredObject? = io {
        val file = file(objectKey) ?: return@io null
        try { StoredObject(Files.size(file), MediaLimits.CONTENT_TYPE) } catch (_: NoSuchFileException) { null }
    }

    /** A missing object is a storage failure here; the caller re-reads the row (a failed peer may have deleted it). */
    override fun read(objectKey: String, maxBytes: Int): ByteArray = io {
        val file = file(objectKey) ?: throw MediaStorageException()
        val bytes = Files.newInputStream(file).use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) throw MediaObjectTooLarge()
        bytes
    }

    override fun delete(objectKey: String) { io { file(objectKey)?.let(Files::deleteIfExists) } }

    /**
     * Same failure contract as GCS: IO problems surface as [MediaStorageException]. Hard links are required; a volume
     * without them (exFAT, some bind mounts) throws UnsupportedOperationException, mapped the same way.
     */
    private fun <T> io(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw MediaStorageException(error)
    } catch (error: UnsupportedOperationException) {
        throw MediaStorageException(error)
    }

    /** Created once per directory and published with a hard link so two starting processes agree on one key. */
    private fun loadOrCreateKey(path: Path): ByteArray {
        if (Files.exists(path)) return Files.readAllBytes(path)
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".signing-key-", ".part")
        try {
            Files.write(temp, ByteArray(32).also(SecureRandom()::nextBytes))
            try { Files.createLink(path, temp) } catch (_: FileAlreadyExistsException) { }
        } finally {
            Files.deleteIfExists(temp)
        }
        return Files.readAllBytes(path)
    }

    fun acceptUpload(objectKey: String, query: Map<String, String>, header: (String) -> String?, body: InputStream): LocalUploadResult {
        val target = file(objectKey) ?: return LocalUploadResult.FORBIDDEN
        if (!authorized("PUT", objectKey, query)) return LocalUploadResult.FORBIDDEN
        if (MediaKeys.uploadHeaders(MediaLimits.CONTENT_TYPE).any { (name, value) -> header(name) != value }) return LocalUploadResult.FORBIDDEN
        if (Files.exists(target)) return LocalUploadResult.EXISTS
        return io {
            Files.createDirectories(target.parent)
            val temp = Files.createTempFile(target.parent, ".upload-", ".part")
            try {
                val size = Files.newOutputStream(temp).use { out -> body.readNBytes(MediaLimits.MAX_BYTES + 1).also(out::write).size }
                when {
                    // GCS rejects an empty body as a bad request, not as too large.
                    size == 0 -> LocalUploadResult.EMPTY
                    size > MediaLimits.MAX_BYTES -> LocalUploadResult.TOO_LARGE
                    else -> try { publish(target, temp); LocalUploadResult.STORED } catch (_: FileAlreadyExistsException) { LocalUploadResult.EXISTS }
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    /** Bytes for a valid GET signature. An object deleted after signing (cleanup, failed completion) is [LocalRead.Missing]. */
    fun readSigned(objectKey: String, query: Map<String, String>): LocalRead {
        val file = file(objectKey) ?: return LocalRead.Forbidden
        if (!authorized("GET", objectKey, query)) return LocalRead.Forbidden
        return try { LocalRead.Found(Files.readAllBytes(file)) } catch (_: NoSuchFileException) { LocalRead.Missing }
            catch (error: IOException) { throw MediaStorageException(error) }
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
