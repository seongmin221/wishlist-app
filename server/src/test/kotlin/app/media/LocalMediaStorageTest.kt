package app.media

import app.testutil.MediaFixtures
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LocalMediaStorageTest {
    private val root = Files.createTempDirectory("local-media")
    private var now = Instant.parse("2026-10-13T00:00:00Z")
    private val storage = LocalMediaStorage(root, "http://localhost:8080", clock = { now })
    private val key = MediaKeys.objectKey(UUID.randomUUID(), UUID.randomUUID())

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2); k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
    }
    private fun pathKey(url: String) = URI(url).path.removePrefix("/local/media-objects/")
    private fun put(target: UploadTarget, body: InputStream, headers: Map<String, String> = target.headers, objectKey: String = pathKey(target.url),
                    params: Map<String, String> = query(target.url)) =
        storage.acceptUpload(objectKey, params, { name -> headers.entries.firstOrNull { it.key.equals(name, true) }?.value }, body)

    @Test fun `signed put stores the object once and signed get reads it back`() {
        val bytes = MediaFixtures.jpeg(20, 10)
        val target = storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        assertEquals("http://localhost:8080/local/media-objects/$key", target.url.substringBefore("?"))
        assertEquals(mapOf("Content-Type" to "image/jpeg", "x-goog-if-generation-match" to "0",
            "x-goog-content-length-range" to "1,5242880"), target.headers)
        assertNull(storage.stat(key))
        assertEquals(LocalUploadResult.STORED, put(target, ByteArrayInputStream(bytes)))
        assertEquals(StoredObject(bytes.size.toLong(), "image/jpeg"), storage.stat(key))
        assertContentEquals(bytes, storage.read(key, MediaLimits.MAX_BYTES))
        assertFailsWith<MediaObjectTooLarge> { storage.read(key, bytes.size - 1) }
        assertEquals(LocalUploadResult.EXISTS, put(target, ByteArrayInputStream(MediaFixtures.jpeg(5, 5))))
        assertContentEquals(bytes, storage.read(key, MediaLimits.MAX_BYTES))
        val read = storage.readUrl(key, Duration.ofHours(1))
        assertEquals(now.plus(Duration.ofHours(1)), read.expiresAt)
        assertContentEquals(bytes, (storage.readSigned(pathKey(read.url), query(read.url)) as LocalRead.Found).bytes)
        assertEquals(LocalRead.Forbidden, storage.readSigned(pathKey(read.url), query(read.url) + ("signature" to "x")))
        assertEquals(LocalRead.Forbidden, storage.readSigned(pathKey(read.url), query(target.url)))  // an upload signature cannot read
        storage.delete(key); storage.delete(key)
        assertNull(storage.stat(key))
        // Deleted between signing and reading (cleanup or a failed completion): missing, not a server error.
        assertEquals(LocalRead.Missing, storage.readSigned(pathKey(read.url), query(read.url)))
        // A missing object while reading is a storage failure, matching GCS, not a raw IO exception.
        assertFailsWith<MediaStorageException> { storage.read(key, MediaLimits.MAX_BYTES) }
    }

    @Test fun `upload rejects forged expired header-less and out of range requests`() {
        val target = storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        val body = { ByteArrayInputStream(MediaFixtures.jpeg(4, 4)) }
        assertEquals(LocalUploadResult.FORBIDDEN, put(target, body(), params = query(target.url) + ("signature" to "forged")))
        assertEquals(LocalUploadResult.FORBIDDEN, put(target, body(), objectKey = MediaKeys.objectKey(UUID.randomUUID(), UUID.randomUUID())))
        for (name in target.headers.keys) {
            assertEquals(LocalUploadResult.FORBIDDEN, put(target, body(), headers = target.headers - name), name)
            assertEquals(LocalUploadResult.FORBIDDEN, put(target, body(), headers = target.headers + (name to "other")), name)
        }
        assertEquals(LocalUploadResult.FORBIDDEN, put(target, body(), objectKey = "media/../../etc/passwd"))
        assertEquals(LocalUploadResult.EMPTY, put(target, ByteArrayInputStream(ByteArray(0))))
        assertEquals(LocalUploadResult.TOO_LARGE, put(target, ByteArrayInputStream(ByteArray(MediaLimits.MAX_BYTES + 1))))
        assertNull(storage.stat(key))
        now = now.plusSeconds(901)
        assertEquals(LocalUploadResult.FORBIDDEN, put(target, body()))
        assertNull(storage.stat(key))
    }

    @Test fun `concurrent puts store exactly one complete object and a partial write is never visible`() {
        val target = storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        val gate = CountDownLatch(1)
        val first = MediaFixtures.jpeg(30, 30); val second = MediaFixtures.jpeg(31, 31)
        val slow = object : InputStream() {
            val inner = ByteArrayInputStream(first)
            var paused = false
            override fun read(): Int {
                if (!paused) { paused = true; check(gate.await(5, TimeUnit.SECONDS)) }
                return inner.read()
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<LocalUploadResult> { put(target, slow) }
            Thread.sleep(100)
            assertNull(storage.stat(key))
            assertEquals(LocalUploadResult.STORED, put(target, ByteArrayInputStream(second)))
            gate.countDown()
            assertEquals(LocalUploadResult.EXISTS, pending.get(5, TimeUnit.SECONDS))
        } finally { gate.countDown(); pool.shutdown() }
        assertContentEquals(second, storage.read(key, MediaLimits.MAX_BYTES))
        assertEquals(1, Files.list(root.resolve(key).parent).use { it.count() })
    }

    @Test fun `filesystem failures while accepting an upload are storage errors`() {
        val blocked = Files.createTempFile("not-a-directory", ".tmp")
        val broken = LocalMediaStorage(blocked, "http://localhost:8080", secret = ByteArray(32), clock = { now })
        val target = broken.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        assertFailsWith<MediaStorageException> {
            broken.acceptUpload(pathKey(target.url), query(target.url), { name -> target.headers.entries.firstOrNull { it.key.equals(name, true) }?.value },
                ByteArrayInputStream(MediaFixtures.jpeg(4, 4)))
        }
    }

    @Test fun `a volume without hard links is a storage error`() {
        val noLinks = LocalMediaStorage(root, "http://localhost:8080", clock = { now }, publish = { _, _ -> throw UnsupportedOperationException() })
        val target = noLinks.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        assertFailsWith<MediaStorageException> {
            noLinks.acceptUpload(pathKey(target.url), query(target.url), { name -> target.headers.entries.firstOrNull { it.key.equals(name, true) }?.value },
                ByteArrayInputStream(MediaFixtures.jpeg(4, 4)))
        }
        assertNull(noLinks.stat(key))
    }

    @Test fun `signed urls survive a restart on the same directory`() {
        val target = storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(900))
        val restarted = LocalMediaStorage(root, "http://localhost:8080", clock = { now })
        assertEquals(LocalUploadResult.STORED, restarted.acceptUpload(pathKey(target.url), query(target.url),
            { name -> target.headers.entries.firstOrNull { it.key.equals(name, true) }?.value }, ByteArrayInputStream(MediaFixtures.jpeg(4, 4))))
        val other = LocalMediaStorage(Files.createTempDirectory("other-media"), "http://localhost:8080", clock = { now })
        assertEquals(LocalUploadResult.FORBIDDEN, other.acceptUpload(pathKey(target.url), query(target.url),
            { name -> target.headers.entries.firstOrNull { it.key.equals(name, true) }?.value }, ByteArrayInputStream(MediaFixtures.jpeg(4, 4))))
    }
}
