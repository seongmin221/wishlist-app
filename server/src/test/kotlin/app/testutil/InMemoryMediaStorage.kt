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
    @Volatile var beforeStat: (String) -> Unit = {}
    @Volatile var deleteDelayMillis = 0L
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
        stats.incrementAndGet(); beforeStat(objectKey)
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
        if (deleteDelayMillis > 0) Thread.sleep(deleteDelayMillis)
        if (failDelete) throw MediaStorageException()
        objects.remove(objectKey); deletes += objectKey
    }

    /** Signer reading straight from this fake without cache. */
    val signer = MediaUrlSigner { key -> runCatching { readUrl(key, MediaLimits.READ_VALIDITY) }.getOrNull() }

    private fun delay() { if (signDelayMillis > 0) Thread.sleep(signDelayMillis) }
}

/** Media wiring for route tests; signing goes straight to [storage]. */
fun mediaWiring(source: javax.sql.DataSource, storage: MediaStorage = InMemoryMediaStorage(),
                signer: MediaUrlSigner = MediaUrlSigner { key -> runCatching { storage.readUrl(key, MediaLimits.READ_VALIDITY) }.getOrNull() }) =
    app.http.MediaWiring(MediaService(source, storage, signer, MediaImageValidator(), MediaSigningExecutor()), signer)

/** Signer for routes whose tests do not involve user images. */
val NO_MEDIA = MediaUrlSigner { null }

/** Inserts a media row for [owner] in [status] and returns its id and object key. */
fun insertMedia(source: javax.sql.DataSource, owner: java.util.UUID, status: String = "READY"): Pair<java.util.UUID, String> {
    val id = java.util.UUID.randomUUID()
    val key = MediaKeys.objectKey(owner, id)
    analysisSql(source, "insert into app_users(id) values ('$owner') on conflict do nothing")
    analysisSql(source, """insert into media_assets(id,owner_id,idempotency_key,request_fingerprint,object_key,content_type,
        declared_byte_size,declared_width,declared_height,upload_expires_at,status,byte_size,width,height,ready_at,failure_reason,failed_at)
        values ('$id','$owner','${java.util.UUID.randomUUID()}','${"a".repeat(64)}','$key','image/jpeg',100,10,10,
        clock_timestamp(),'$status',100,10,10,clock_timestamp(),
        ${if (status == "FAILED") "'NOT_JPEG',clock_timestamp()" else "null,null"})""")
    return id to key
}
