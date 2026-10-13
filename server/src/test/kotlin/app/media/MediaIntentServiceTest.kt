package app.media

import app.testutil.InMemoryMediaStorage
import app.testutil.analysisScalar
import app.testutil.analysisSql
import app.testutil.withAnalysisDatabase
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class MediaIntentServiceTest {
    private val request = IntentRequest(MediaLimits.CONTENT_TYPE, 812_345, 2048, 1536)

    private fun service(source: DataSource, storage: InMemoryMediaStorage, signing: MediaSigningExecutor = MediaSigningExecutor()) =
        MediaService(source, storage, storage.signer, MediaImageValidator(), signing)

    private fun dbNow(source: DataSource) = OffsetDateTime.parse(analysisScalar(source, "select to_char(clock_timestamp() at time zone 'utc','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")).toInstant()

    @Test fun `new intent stores a pending row and signs a fifteen minute upload`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID(); val key = UUID.randomUUID()
        val before = dbNow(source)
        val result = service(source, storage).createIntent(owner, key, request)
        assertFalse(result.replayed)
        val asset = result.view.asset
        assertEquals(MediaStatus.PENDING, asset.status)
        assertEquals(MediaKeys.objectKey(owner, asset.id), asset.objectKey)
        assertEquals(DeclaredImage(812_345, 2048, 1536), asset.declared)
        val validity = Duration.between(before, asset.uploadExpiresAt)
        assertTrue(validity > Duration.ofMinutes(15).minusSeconds(5) && validity <= Duration.ofMinutes(15).plusSeconds(5), validity.toString())
        val upload = assertNotNull(result.view.upload)
        assertEquals(asset.uploadExpiresAt, upload.expiresAt)
        assertEquals(MediaKeys.uploadHeaders(MediaLimits.CONTENT_TYPE), upload.headers)
        assertNull(result.view.display)
        assertEquals(1, storage.uploadSigns.get())
        assertEquals("PENDING", analysisScalar(source, "select status from media_assets where id='${asset.id}'"))
    }

    @Test fun `replays follow the stored state and never extend the upload expiry`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val media = service(source, storage)
        val owner = UUID.randomUUID(); val key = UUID.randomUUID()
        val first = media.createIntent(owner, key, request).view
        val replay = media.createIntent(owner, key, request)
        assertTrue(replay.replayed)
        assertEquals(first.asset.id, replay.view.asset.id)
        assertEquals(first.asset.uploadExpiresAt, replay.view.upload!!.expiresAt)
        assertNotEquals(first.upload!!.url, replay.view.upload!!.url)
        assertEquals(2, storage.uploadSigns.get())
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<MediaException> { media.createIntent(owner, key, request.copy(byteSize = 1)) }.code)
        assertEquals(1, analysisScalar(source, "select count(*) from media_assets")!!.toInt())

        val id = first.asset.id
        analysisSql(source, "update media_assets set status='READY',byte_size=812345,width=2048,height=1536,ready_at=clock_timestamp() where id='$id'")
        val ready = media.createIntent(owner, key, request)
        assertTrue(ready.replayed)
        assertEquals(MediaStatus.READY, ready.view.asset.status)
        assertNull(ready.view.upload)
        assertNotNull(ready.view.display)

        analysisSql(source, "update media_assets set status='FAILED',failure_reason='METADATA_PRESENT',failed_at=clock_timestamp() where id='$id'")
        val failed = assertFailsWith<MediaException> { media.createIntent(owner, key, request) }
        assertEquals("MEDIA_INVALID_IMAGE", failed.code); assertEquals(MediaFailureReason.METADATA_PRESENT, failed.reason)

        for (state in listOf("status='PENDING',failure_reason=null,failed_at=null,upload_expires_at=clock_timestamp()-interval '1 second'",
            "status='DELETING'", "status='DELETED',deleted_at=clock_timestamp()"))
        {
            analysisSql(source, "update media_assets set $state where id='$id'")
            assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> { media.createIntent(owner, key, request) }.code, state)
        }
    }

    @Test fun `ten live pending intents block the next one until the earliest expires`() = withAnalysisDatabase { source ->
        val media = service(source, InMemoryMediaStorage())
        val owner = UUID.randomUUID()
        repeat(10) { media.createIntent(owner, UUID.randomUUID(), request) }
        analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()+interval '100 seconds' where id=(select id from media_assets order by created_at limit 1)")
        val limited = assertFailsWith<MediaException> { media.createIntent(owner, UUID.randomUUID(), request) }
        assertEquals("MEDIA_UPLOAD_LIMIT_REACHED", limited.code)
        assertTrue(limited.retryAfterSeconds!! in 99..100, limited.retryAfterSeconds.toString())
        media.createIntent(UUID.randomUUID(), UUID.randomUUID(), request)  // other owners are independent
        analysisSql(source, "update media_assets set upload_expires_at=clock_timestamp()-interval '1 second' where id=(select id from media_assets where owner_id='$owner' order by created_at limit 1)")
        media.createIntent(owner, UUID.randomUUID(), request)
        analysisSql(source, """update media_assets set status='FAILED',failure_reason='NOT_JPEG',failed_at=clock_timestamp()
            where id=(select id from media_assets where owner_id='$owner' and status='PENDING' and upload_expires_at>clock_timestamp() order by created_at limit 1)""")
        analysisSql(source, """update media_assets set status='READY',byte_size=1,width=1,height=1,ready_at=clock_timestamp()
            where id=(select id from media_assets where owner_id='$owner' and status='PENDING' and upload_expires_at>clock_timestamp() order by created_at limit 1)""")
        media.createIntent(owner, UUID.randomUUID(), request)
        media.createIntent(owner, UUID.randomUUID(), request)
        assertEquals("MEDIA_UPLOAD_LIMIT_REACHED", assertFailsWith<MediaException> { media.createIntent(owner, UUID.randomUUID(), request) }.code)
    }

    @Test fun `signing failure keeps the row so the same key succeeds later`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage().apply { failSign = true }
        val media = service(source, storage)
        val owner = UUID.randomUUID(); val key = UUID.randomUUID()
        assertEquals("MEDIA_STORAGE_UNAVAILABLE", assertFailsWith<MediaException> { media.createIntent(owner, key, request) }.code)
        assertEquals(1, analysisScalar(source, "select count(*) from media_assets where owner_id='$owner'")!!.toInt())
        storage.failSign = false
        assertTrue(media.createIntent(owner, key, request).replayed)

        storage.signDelayMillis = 3_000
        val slow = MediaService(source, storage, storage.signer, MediaImageValidator(), MediaSigningExecutor(wait = Duration.ofMillis(300)))
        val started = System.nanoTime()
        assertEquals("MEDIA_STORAGE_UNAVAILABLE", assertFailsWith<MediaException> { slow.createIntent(owner, UUID.randomUUID(), request) }.code)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
    }

    @Test fun `invalid declarations are rejected before any row is written`() = withAnalysisDatabase { source ->
        val media = service(source, InMemoryMediaStorage())
        for ((bad, code) in listOf(
            request.copy(byteSize = 0) to "INVALID_MEDIA_REQUEST", request.copy(width = 0) to "INVALID_MEDIA_REQUEST",
            request.copy(contentType = "image/png", byteSize = 6_000_000) to "UNSUPPORTED_MEDIA_TYPE",
            request.copy(byteSize = 5_242_881, width = 4096) to "MEDIA_TOO_LARGE", request.copy(height = 2049) to "MEDIA_DIMENSIONS_EXCEEDED",
        )) assertEquals(code, assertFailsWith<MediaException> { media.createIntent(UUID.randomUUID(), UUID.randomUUID(), bad) }.code)
        assertEquals(0, analysisScalar(source, "select count(*) from media_assets")!!.toInt())
    }

    @Test fun `concurrent requests with one key create one row`() = withAnalysisDatabase { source ->
        val media = service(source, InMemoryMediaStorage())
        val owner = UUID.randomUUID(); val key = UUID.randomUUID()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { pool.submit(Callable { start.await(); media.createIntent(owner, key, request) }) }
            start.countDown()
            val done = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, done.map { it.view.asset.id }.distinct().size)
            assertEquals(listOf(false, true), done.map { it.replayed }.sorted())
        } finally { pool.shutdown() }
        assertEquals(1, analysisScalar(source, "select count(*) from media_assets")!!.toInt())
    }

    @Test fun `a reused key with a different invalid body is a key conflict not a value error`() = withAnalysisDatabase { source ->
        val media = service(source, InMemoryMediaStorage())
        val owner = UUID.randomUUID(); val key = UUID.randomUUID()
        media.createIntent(owner, key, request)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertFailsWith<MediaException> { media.createIntent(owner, key, request.copy(byteSize = 6_000_000)) }.code)
    }
}
