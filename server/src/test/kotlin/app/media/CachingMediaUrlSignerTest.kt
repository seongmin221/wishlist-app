package app.media

import app.testutil.InMemoryMediaStorage
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CachingMediaUrlSignerTest {
    private var now = Instant.parse("2026-10-13T00:00:00Z")
    private val storage = InMemoryMediaStorage { now }

    @Test fun `reuses a url while at least forty five minutes remain`() {
        MediaSigningExecutor().use { signing ->
            val signer = CachingMediaUrlSigner(storage, signing, { now })
            val first = assertNotNull(signer.sign("a"))
            assertEquals(now.plus(Duration.ofHours(1)), first.expiresAt)
            now = now.plus(Duration.ofMinutes(15))
            assertEquals(first, signer.sign("a"))
            assertEquals(1, storage.readSigns.get())
            now = now.plusSeconds(1)
            assertNotEquals(first, signer.sign("a"))
            assertEquals(2, storage.readSigns.get())
            storage.failSign = true
            assertNull(signer.sign("b"))
            storage.failSign = false
            assertNotNull(signer.sign("b"))
            assertEquals(4, storage.readSigns.get())  // the failure was not cached
        }
    }

    @Test fun `evicts the earliest expiry past the entry limit`() {
        MediaSigningExecutor().use { signing ->
            val signer = CachingMediaUrlSigner(storage, signing, { now }, maxEntries = 2)
            signer.sign("a"); now = now.plusSeconds(1); signer.sign("b"); now = now.plusSeconds(1); signer.sign("c")
            val before = storage.readSigns.get()
            signer.sign("b"); signer.sign("c")
            assertEquals(before, storage.readSigns.get())
            signer.sign("a")
            assertEquals(before + 1, storage.readSigns.get())
        }
    }

    @Test fun `one response waits at most the budget and late signatures fill the cache`() {
        storage.signDelayMillis = 1_000
        MediaSigningExecutor(threads = 8, queue = 64, wait = Duration.ofMillis(1_500)).use { signing ->
            val signer = CachingMediaUrlSigner(storage, signing, { now })
            val keys = (1..40).map { "k$it" }.toSet()
            val started = System.nanoTime()
            val urls = signer.signAll(keys)
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofMillis(2_200), elapsed.toString())
            val signedNow = keys.count { urls.imageUrl(it, null) != null }
            assertEquals(8, signedNow)
            Thread.sleep(5_500)
            storage.signDelayMillis = 0
            val calls = storage.readSigns.get()
            val later = signer.signAll(keys)
            assertEquals(40, keys.count { later.imageUrl(it, null) != null })
            assertEquals(calls, storage.readSigns.get())
        }
    }

    @Test fun `concurrent responses share one signature per key`() {
        storage.signDelayMillis = 300
        MediaSigningExecutor().use { signing ->
            val signer = CachingMediaUrlSigner(storage, signing, { now })
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val results = List(4) { pool.submit(Callable { start.await(); signer.signAll(setOf("shared")) }) }
                start.countDown()
                results.forEach { assertNotNull(it.get(5, TimeUnit.SECONDS).imageUrl("shared", null)) }
            } finally { pool.shutdown() }
            assertEquals(1, storage.readSigns.get())
        }
    }

    @Test fun `a user image whose signing failed never falls back to the extracted image`() {
        val urls = MediaUrls(mapOf("k" to SignedRead("signed", now)))
        assertEquals("signed", urls.imageUrl("k", "https://shop.example/x.jpg"))
        assertNull(urls.imageUrl("missing", "https://shop.example/x.jpg"))
        assertEquals("https://shop.example/x.jpg", urls.imageUrl(null, "https://shop.example/x.jpg"))
    }
}
