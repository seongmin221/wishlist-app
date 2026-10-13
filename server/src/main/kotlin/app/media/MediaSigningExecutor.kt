package app.media

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide pool for URL signing. Remote signBlob calls have no client timeout, so callers wait at most [wait] and a
 * stuck call keeps one bounded thread instead of piling up per request. A full pool fails fast.
 */
class MediaSigningExecutor(threads: Int = 8, queue: Int = 64, val wait: Duration = Duration.ofSeconds(2)) : AutoCloseable {
    private val counter = AtomicInteger()
    private val pool = ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queue)) { task ->
        Thread(task, "media-signing-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<*>>()

    /** Null when the pool is saturated or closed. */
    fun <T> submit(block: () -> T): CompletableFuture<T>? = try {
        CompletableFuture.supplyAsync(block, pool)
    } catch (_: RejectedExecutionException) { null }

    /** One running call per key; concurrent callers share it. The entry clears when the call finishes. */
    @Suppress("UNCHECKED_CAST")
    fun <T> shared(key: String, block: () -> T): CompletableFuture<T>? {
        inFlight[key]?.let { return it as CompletableFuture<T> }
        val created = CompletableFuture<T>()
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) return existing as CompletableFuture<T>
        val running = submit(block)
        if (running == null) {
            // Callers that joined between the insert and this point must fail now, not at their deadline.
            inFlight.remove(key, created); created.completeExceptionally(RejectedExecutionException()); return null
        }
        running.whenComplete { value, error ->
            inFlight.remove(key, created)
            if (error != null) created.completeExceptionally(error) else created.complete(value)
        }
        return created
    }

    /** Waits until [deadlineNanos] (System.nanoTime); failure or timeout is null. */
    fun <T> await(future: CompletableFuture<T>?, deadlineNanos: Long): T? {
        if (future == null) return null
        return try {
            future.get(maxOf(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) { null } catch (_: ExecutionException) { null }
    }

    fun <T> await(block: () -> T): T? = await(submit(block), System.nanoTime() + wait.toNanos())

    override fun close() {
        pool.shutdownNow()
        // Tasks dropped from the queue never complete; release their waiters.
        inFlight.values.forEach { it.completeExceptionally(RejectedExecutionException()) }
        inFlight.clear()
    }
}
