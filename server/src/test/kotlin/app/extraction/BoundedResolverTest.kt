package app.extraction

import app.analysis.WorkerDisposition
import app.analysis.WorkerExecution
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedResolverTest {
    private val address = InetAddress.getByName("93.184.215.14")

    @Test fun `successful lookup returns addresses`() = BoundedResolver(lookup = { listOf(address) }).use { resolver ->
        assertEquals(listOf(address), resolver("shop.example"))
    }

    @Test fun `unknown host and empty answers become dns failures`() {
        BoundedResolver(lookup = { throw java.net.UnknownHostException(it) }).use { assertFailsWith<DnsLookupFailed> { it("nx.example") } }
        BoundedResolver(lookup = { emptyList() }).use { assertFailsWith<DnsLookupFailed> { it("empty.example") } }
    }

    @Test fun `a hanging lookup is abandoned when the wait bound passes`() {
        val release = CountDownLatch(1)
        BoundedResolver(maxWaitMillis = 100, lookup = { release.await(); listOf(address) }).use { resolver ->
            val started = System.nanoTime()
            assertFailsWith<DnsLookupFailed> { resolver("slow.example") }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            release.countDown()
        }
    }

    @Test fun `the remaining worker processing time bounds the wait`() {
        val release = CountDownLatch(1)
        BoundedResolver(lookup = { release.await(); listOf(address) }).use { resolver ->
            WorkerExecution(timeoutMillis = 2_000, processingMillis = 150).use { execution ->
                var failure: Throwable? = null
                val started = System.nanoTime()
                execution.run { failure = runCatching { resolver("slow.example") }.exceptionOrNull(); WorkerDisposition.ACKNOWLEDGE }
                assertTrue(failure is DnsLookupFailed || failure is app.analysis.ProcessingDeadlineExceeded, failure.toString())
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
            }
            release.countDown()
        }
    }

    @Test fun `a saturated resolver rejects immediately and a closed one rejects every call`() {
        val release = CountDownLatch(1)
        val resolver = BoundedResolver(threads = 1, queueCapacity = 1, maxWaitMillis = 5_000, lookup = { release.await(); listOf(address) })
        val blocked = List(2) { Thread { runCatching { resolver("busy.example") } }.apply { start() } }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (resolver.queued() < 1 && System.nanoTime() < until) Thread.yield()
        val started = System.nanoTime()
        assertFailsWith<DnsLookupFailed> { resolver("third.example") }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        release.countDown(); blocked.forEach { it.join(5_000) }
        resolver.close()
        assertFailsWith<DnsLookupFailed> { resolver("closed.example") }
    }
}
