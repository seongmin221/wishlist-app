package app.media

import app.media.MediaFailureReason.*
import app.testutil.MediaFixtures
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaImageValidatorTest {
    private val validator = MediaImageValidator()
    private fun declared(bytes: ByteArray, width: Int, height: Int) = DeclaredImage(bytes.size, width, height)
    private fun invalid(reason: MediaFailureReason) = ImageVerdict.Invalid(reason)

    @Test fun `precheck rejects size and content type before reading bytes`() {
        val declared = DeclaredImage(1000, 10, 10)
        assertNull(validator.precheck(StoredObject(1000, "image/jpeg"), declared))
        assertEquals(SIZE_MISMATCH, validator.precheck(StoredObject(999, "image/jpeg"), declared))
        assertEquals(SIZE_MISMATCH, validator.precheck(StoredObject(MediaLimits.MAX_BYTES + 1L, "image/jpeg"), declared.copy(byteSize = MediaLimits.MAX_BYTES)))
        assertEquals(CONTENT_TYPE_MISMATCH, validator.precheck(StoredObject(1000, "image/png"), declared))
        assertEquals(CONTENT_TYPE_MISMATCH, validator.precheck(StoredObject(1000, null), declared))
    }

    @Test fun `verify accepts real baseline progressive grayscale and icc images`() {
        for ((bytes, size) in listOf(
            MediaFixtures.jpeg(2048, 1536) to (2048 to 1536), MediaFixtures.progressive(300, 200) to (300 to 200),
            MediaFixtures.grayscale(50, 40) to (50 to 40), MediaFixtures.iccProfile(MediaFixtures.jpeg(20, 10)) to (20 to 10),
        )) assertEquals(ImageVerdict.Valid(bytes.size, size.first, size.second), validator.verify(bytes, declared(bytes, size.first, size.second)))
    }

    @Test fun `verify reports the first failed rule`() {
        val ok = MediaFixtures.jpeg(64, 48)
        assertEquals(invalid(SIZE_MISMATCH), validator.verify(ok, DeclaredImage(ok.size + 1, 64, 48)))
        assertEquals(invalid(NOT_JPEG), MediaFixtures.png(64, 48).let { validator.verify(it, declared(it, 64, 48)) })
        assertEquals(invalid(UNSUPPORTED_JPEG), MediaFixtures.synthetic(components = 4).let { validator.verify(it, declared(it, 16, 16)) })
        assertEquals(invalid(METADATA_PRESENT), MediaFixtures.exif(ok).let { validator.verify(it, declared(it, 64, 48)) })
        assertEquals(invalid(DIMENSIONS_EXCEEDED), MediaFixtures.jpeg(2049, 10).let { validator.verify(it, declared(it, 2049, 10)) })
        assertEquals(invalid(DIMENSIONS_EXCEEDED), MediaFixtures.synthetic(height = 0).let { validator.verify(it, declared(it, 16, 1)) })
        assertEquals(invalid(DIMENSIONS_MISMATCH), validator.verify(ok, declared(ok, 48, 64)))
        val truncated = MediaFixtures.truncatedAfterSos(MediaFixtures.jpeg(256, 256))
        assertEquals(invalid(DECODE_FAILED), validator.verify(truncated, declared(truncated, 256, 256)))
        assertEquals(invalid(DECODE_FAILED), MediaFixtures.synthetic().let { validator.verify(it, declared(it, 16, 16)) })
    }

    @Test fun `gate admits four concurrent verifications and rejects the fifth after a short wait`() {
        val gate = MediaImageValidator(maxConcurrent = 4)
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { pool.submit { gate.gated { entered.countDown(); release.await() } } }
            check(entered.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val busy = assertFailsWith<MediaException> { gate.gated { } }
            assertEquals("MEDIA_BUSY", busy.code)
            val waited = java.time.Duration.ofNanos(System.nanoTime() - started)
            assertTrue(waited >= java.time.Duration.ofMillis(900) && waited < java.time.Duration.ofSeconds(3), waited.toString())
            release.countDown()
        } finally {
            release.countDown(); pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        assertEquals(1, gate.gated { 1 })
    }

}
