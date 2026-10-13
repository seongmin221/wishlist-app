package app.media

import app.testutil.InMemoryMediaStorage
import app.testutil.MediaFixtures
import app.testutil.analysisScalar
import app.testutil.analysisSql
import app.testutil.withAnalysisDatabase
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class MediaCompletionServiceTest {
    private val image = MediaFixtures.jpeg(64, 48)

    private fun service(source: DataSource, storage: InMemoryMediaStorage, validator: MediaImageValidator = MediaImageValidator(),
                        beforeApply: (UUID) -> Unit = {}) =
        MediaService(source, storage, storage.signer, validator, MediaSigningExecutor(), beforeApply)

    /** Creates an intent for [bytes] declared as 64×48 and optionally uploads them. */
    private fun intent(media: MediaService, storage: InMemoryMediaStorage, owner: UUID, bytes: ByteArray = image, upload: Boolean = true): MediaAsset {
        val asset = media.createIntent(owner, UUID.randomUUID(), IntentRequest(MediaLimits.CONTENT_TYPE, bytes.size, 64, 48)).view.asset
        if (upload) storage.put(asset.objectKey, bytes)
        return asset
    }

    private fun status(source: DataSource, id: UUID) = analysisScalar(source, "select status from media_assets where id='$id'")

    @Test fun `valid upload becomes ready and replays without reading again`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val asset = intent(media, storage, owner)
        val view = media.complete(owner, asset.id)
        assertEquals(MediaStatus.READY, view.asset.status)
        assertEquals(Triple(image.size, 64, 48), Triple(view.asset.byteSize, view.asset.width, view.asset.height))
        assertNull(view.upload)
        assertNotNull(view.display)
        assertEquals("READY", status(source, asset.id))
        assertEquals("t", analysisScalar(source, "select ready_at is not null from media_assets where id='${asset.id}'"))
        val reads = storage.reads.get()
        assertEquals(MediaStatus.READY, media.complete(owner, asset.id).asset.status)
        assertEquals(reads, storage.reads.get())
    }

    @Test fun `unknown other owner missing object storage failure and expiry`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val asset = intent(media, storage, owner, upload = false)
        assertEquals("MEDIA_NOT_FOUND", assertFailsWith<MediaException> { media.complete(UUID.randomUUID(), asset.id) }.code)
        assertEquals("MEDIA_NOT_FOUND", assertFailsWith<MediaException> { media.complete(owner, UUID.randomUUID()) }.code)
        assertEquals("MEDIA_OBJECT_MISSING", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        assertEquals("PENDING", status(source, asset.id))
        storage.put(asset.objectKey, image)
        storage.failStat = true
        assertEquals("MEDIA_STORAGE_UNAVAILABLE", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        storage.failStat = false; storage.failRead = true
        assertEquals("MEDIA_STORAGE_UNAVAILABLE", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        assertEquals("PENDING", status(source, asset.id))
        storage.failRead = false
        assertEquals("MEDIA_BUSY", assertFailsWith<MediaException> { service(source, storage, MediaImageValidator(0)).complete(owner, asset.id) }.code)
        assertEquals("PENDING", status(source, asset.id))
        analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()-interval '1 second' where id='${asset.id}'")
        assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        for (state in listOf("status='DELETING'", "status='DELETED',deleted_at=clock_timestamp()")) {
            analysisSql(source, "update media_assets set $state where id='${asset.id}'")
            assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        }
    }

    @Test fun `invalid uploads fail with their reason delete the object and replay the same error`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val cases = listOf(
            MediaFixtures.exif(image) to MediaFailureReason.METADATA_PRESENT,
            MediaFixtures.png(64, 48) to MediaFailureReason.NOT_JPEG,
            MediaFixtures.jpeg(48, 64) to MediaFailureReason.DIMENSIONS_MISMATCH,
        )
        for ((bytes, reason) in cases) {
            val asset = intent(media, storage, owner, bytes)
            val error = assertFailsWith<MediaException> { media.complete(owner, asset.id) }
            assertEquals("MEDIA_INVALID_IMAGE", error.code); assertEquals(reason, error.reason)
            assertEquals("FAILED", status(source, asset.id))
            assertEquals(reason.name, analysisScalar(source, "select failure_reason from media_assets where id='${asset.id}'"))
            assertTrue(asset.objectKey in storage.deletes)
            val reads = storage.reads.get()
            assertEquals(reason, assertFailsWith<MediaException> { media.complete(owner, asset.id) }.reason)
            assertEquals(reads, storage.reads.get())
        }
        val wrongSize = intent(media, storage, owner, upload = false)
        storage.put(wrongSize.objectKey, image + byteArrayOf(0))
        assertEquals(MediaFailureReason.SIZE_MISMATCH, assertFailsWith<MediaException> { media.complete(owner, wrongSize.id) }.reason)
        val wrongType = intent(media, storage, owner, upload = false)
        storage.put(wrongType.objectKey, image, "image/png")
        assertEquals(MediaFailureReason.CONTENT_TYPE_MISMATCH, assertFailsWith<MediaException> { media.complete(owner, wrongType.id) }.reason)
        storage.failDelete = true
        val undeleted = intent(media, storage, owner, MediaFixtures.comment(image))
        assertEquals("MEDIA_INVALID_IMAGE", assertFailsWith<MediaException> { media.complete(owner, undeleted.id) }.code)
        assertEquals("FAILED", status(source, undeleted.id))
    }

    @Test fun `two concurrent completions apply once and both see ready`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val barrier = CyclicBarrier(2)
        val media = service(source, storage, beforeApply = { barrier.await(5, TimeUnit.SECONDS) })
        val owner = UUID.randomUUID()
        val asset = intent(service(source, storage), storage, owner)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val views = List(2) { pool.submit(Callable { media.complete(owner, asset.id) }) }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(MediaStatus.READY, MediaStatus.READY), views.map { it.asset.status })
            assertEquals(2, storage.reads.get())
        } finally { pool.shutdown() }
        assertEquals("READY", status(source, asset.id))
    }

    @Test fun `a completion that finds the object deleted by a failed peer answers with the stored failure`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val asset = intent(media, storage, owner, MediaFixtures.exif(image))
        var first = true
        storage.beforeStat = {
            if (first) {
                first = false
                assertFailsWith<MediaException> { media.complete(owner, asset.id) }
            }
        }
        val error = assertFailsWith<MediaException> { media.complete(owner, asset.id) }
        assertEquals("MEDIA_INVALID_IMAGE", error.code)
        assertEquals(MediaFailureReason.METADATA_PRESENT, error.reason)
    }

    @Test fun `display signing failure still reports ready and expired or failed uploads can start over`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val asset = intent(media, storage, owner)
        storage.failSign = true
        val view = media.complete(owner, asset.id)
        assertEquals(MediaStatus.READY, view.asset.status); assertNull(view.display)
        storage.failSign = false

        val expired = intent(media, storage, owner)
        analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()-interval '1 second' where id='${expired.id}'")
        assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> { media.complete(owner, expired.id) }.code)
        assertEquals(MediaStatus.READY, media.complete(owner, intent(media, storage, owner).id).asset.status)
        val failed = intent(media, storage, owner, MediaFixtures.exif(image))
        assertFailsWith<MediaException> { media.complete(owner, failed.id) }
        assertEquals(MediaStatus.READY, media.complete(owner, intent(media, storage, owner).id).asset.status)
    }

    @Test fun `a slow storage read does not hold a verification permit`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        val setup = service(source, storage)
        val first = intent(setup, storage, owner); val second = intent(setup, storage, owner)
        // The first read is slow; the second completes and verifies while the first is still reading.
        storage.readDelayMillis = { key -> if (key == first.objectKey) 800 else 0 }
        val media = service(source, storage, MediaImageValidator(maxConcurrent = 1))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit(Callable { media.complete(owner, first.id) })
            Thread.sleep(200)
            val fast = pool.submit(Callable { media.complete(owner, second.id) })
            val views = listOf(slow, fast).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(MediaStatus.READY, MediaStatus.READY), views.map { it.asset.status })
        } finally { pool.shutdown() }
    }

    @Test fun `expiry is judged again when the result is applied`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        val asset = intent(service(source, storage), storage, owner)
        val media = service(source, storage, beforeApply = { id ->
            analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()-interval '1 second' where id='$id'")
        })
        assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> { media.complete(owner, asset.id) }.code)
        assertEquals("PENDING", status(source, asset.id))
    }

    @Test fun `ready media starts unreferenced for cleanup`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage(); val media = service(source, storage)
        val owner = UUID.randomUUID()
        val asset = intent(media, storage, owner)
        media.complete(owner, asset.id)
        assertEquals("t", analysisScalar(source, "select unreferenced_since = ready_at from media_assets where id='${asset.id}'"))
    }
}
