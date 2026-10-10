package app.extraction

import app.analysis.WorkerExecution
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Target DNS resolution failed, timed out or could not be scheduled; unlike a blocked address this may recover. */
class DnsLookupFailed : RuntimeException("DNS lookup failed")

/**
 * InetAddress lookups have no timeout and ignore interrupts, so they run on a small bounded pool and the caller
 * waits at most the remaining processing time (5s cap). Saturation is rejected immediately instead of queueing.
 */
class BoundedResolver(
    threads: Int = 4,
    queueCapacity: Int = 16,
    private val maxWaitMillis: Long = 5_000,
    private val lookup: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) : (String) -> List<InetAddress>, AutoCloseable {
    private val executor = ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
        ThreadFactory { task -> Thread(task, "dns-resolver").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    override fun invoke(host: String): List<InetAddress> {
        val wait = WorkerExecution.remaining(Duration.ofMillis(maxWaitMillis))
        val future = try { executor.submit<List<InetAddress>> { lookup(host) } } catch (_: RejectedExecutionException) { throw DnsLookupFailed() }
        val addresses = try {
            future.get(wait.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true); throw DnsLookupFailed()
        } catch (_: ExecutionException) {
            throw DnsLookupFailed()
        }
        return addresses.ifEmpty { throw DnsLookupFailed() }
    }

    internal fun queued(): Int = executor.queue.size

    override fun close() { executor.shutdownNow() }

    companion object {
        /** Shared instance for tests and convenience constructors; runtime roles own their resolver. */
        val default by lazy { BoundedResolver() }
    }
}
