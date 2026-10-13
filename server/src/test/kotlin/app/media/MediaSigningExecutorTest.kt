package app.media

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MediaSigningExecutorTest {
    @Test fun `await gives up after the wait while the call still completes`() {
        MediaSigningExecutor(threads = 1, queue = 1, wait = Duration.ofMillis(200)).use { executor ->
            val finished = CountDownLatch(1)
            val started = System.nanoTime()
            assertNull(executor.await { Thread.sleep(600); finished.countDown(); "late" })
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(500))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals("ok", executor.await { "ok" })
            assertNull(executor.await<String> { throw IllegalStateException("boom") })
        }
    }

    @Test fun `shared calls for one key run once`() {
        MediaSigningExecutor(threads = 2, queue = 8, wait = Duration.ofSeconds(2)).use { executor ->
            val runs = AtomicInteger()
            val release = CountDownLatch(1)
            val futures = (1..5).map { executor.shared("key") { runs.incrementAndGet(); release.await(); "signed" } }
            release.countDown()
            assertEquals(List(5) { "signed" }, futures.map { executor.await(it, System.nanoTime() + 2_000_000_000) })
            assertEquals(1, runs.get())
            assertEquals("again", executor.await(executor.shared("key") { "again" }, System.nanoTime() + 2_000_000_000))
        }
    }

    @Test fun `saturated or closed executor fails immediately`() {
        val executor = MediaSigningExecutor(threads = 1, queue = 1, wait = Duration.ofSeconds(2))
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            assertNotNull(executor.submit { release.await() })
            assertNotNull(executor.submit { release.await() })
            val started = System.nanoTime()
            assertNull(executor.submit { "rejected" })
            assertNull(executor.await { "rejected" })
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(200))
            // A shared call that cannot be scheduled fails at once for every caller.
            val rejectedStart = System.nanoTime()
            assertNull(executor.await(executor.shared("full") { "x" }, System.nanoTime() + 2_000_000_000))
            assertTrue(Duration.ofNanos(System.nanoTime() - rejectedStart) < Duration.ofMillis(200))
        } finally { release.countDown(); pool.shutdown() }
        executor.close()
        assertNull(executor.await { "closed" })
    }
}
