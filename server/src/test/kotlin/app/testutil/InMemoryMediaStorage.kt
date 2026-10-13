package app.testutil

import app.media.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Bucket fake with failure injection and call counters. */
class InMemoryMediaStorage(var now: () -> Instant = Instant::now) : MediaStorage {
    val objects = ConcurrentHashMap<String, Pair<ByteArray, String?>>()
    @Volatile var failSign = false
    @Volatile var failStat = false
    @Volatile var failRead = false
    @Volatile var failDelete = false
    @Volatile var signDelayMillis = 0L
    val uploadSigns = AtomicInteger()
    val readSigns = AtomicInteger()
    val stats = AtomicInteger()
    val reads = AtomicInteger()
    val deletes = ConcurrentHashMap.newKeySet<String>()
    val deleteCalls = AtomicInteger()

    fun put(objectKey: String, bytes: ByteArray, contentType: String? = MediaLimits.CONTENT_TYPE) { objects[objectKey] = bytes to contentType }

    override fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget {
        uploadSigns.incrementAndGet(); delay()
        if (failSign) throw MediaStorageException()
        return UploadTarget("PUT", "memory://upload/$objectKey?n=${uploadSigns.get()}", MediaKeys.uploadHeaders(contentType), expiresAt)
    }

    override fun readUrl(objectKey: String, validFor: Duration): SignedRead {
        readSigns.incrementAndGet(); delay()
        if (failSign) throw MediaStorageException()
        val expiresAt = now().plus(validFor)
        return SignedRead("memory://read/$objectKey?exp=${expiresAt.epochSecond}", expiresAt)
    }

    override fun stat(objectKey: String): StoredObject? {
        stats.incrementAndGet()
        if (failStat) throw MediaStorageException()
        return objects[objectKey]?.let { (bytes, type) -> StoredObject(bytes.size.toLong(), type) }
    }

    override fun read(objectKey: String, maxBytes: Int): ByteArray {
        reads.incrementAndGet()
        if (failRead) throw MediaStorageException()
        val bytes = objects[objectKey]?.first ?: throw MediaStorageException()
        if (bytes.size > maxBytes) throw MediaObjectTooLarge()
        return bytes
    }

    override fun delete(objectKey: String) {
        deleteCalls.incrementAndGet()
        if (failDelete) throw MediaStorageException()
        objects.remove(objectKey); deletes += objectKey
    }

    /** Signer reading straight from this fake without cache. */
    val signer = MediaUrlSigner { key -> runCatching { readUrl(key, MediaLimits.READ_VALIDITY) }.getOrNull() }

    private fun delay() { if (signDelayMillis > 0) Thread.sleep(signDelayMillis) }
}
