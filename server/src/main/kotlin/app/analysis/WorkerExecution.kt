package app.analysis

import java.time.Duration
import java.util.concurrent.*
import kotlinx.coroutines.CancellationException

object AnalysisTiming {
    const val PROCESSING_SECONDS = 80L
    const val WORKER_SECONDS = 90L
    const val TASK_SECONDS = 105L
    const val LEASE_SECONDS = 120L
}

class ProcessingDeadlineExceeded : RuntimeException("Analysis processing deadline exceeded")

/** Bound the entire synchronous handler, including claim/finish, without a per-request executor. */
class WorkerExecution(
    private val timeoutMillis: Long = AnalysisTiming.WORKER_SECONDS * 1000,
    private val processingMillis: Long = AnalysisTiming.PROCESSING_SECONDS * 1000,
) : AutoCloseable {
    init { require(processingMillis > 0 && processingMillis < timeoutMillis) }
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        ThreadFactory { task -> Thread(task, "analysis-execution").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun run(action: () -> WorkerDisposition): WorkerDisposition {
        val started = System.nanoTime()
        val future = try {
            executor.submit<WorkerDisposition> {
                deadline.set(started + TimeUnit.MILLISECONDS.toNanos(processingMillis))
                try { if (expired()) WorkerDisposition.RETRY else action() } finally { deadline.remove() }
            }
        } catch (_: RejectedExecutionException) { return WorkerDisposition.RETRY }
        return try {
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started)
            if (remaining <= 0) throw TimeoutException()
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            executor.purge()
            // An interrupted handler retries durably; unresponsive work is recovered by its DB lease.
            WorkerDisposition.RETRY
        } catch (cause: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("Worker request interrupted").apply { initCause(cause) }
        } catch (cause: ExecutionException) {
            throw cause.cause ?: cause
        }
    }

    override fun close() { executor.shutdownNow() }

    companion object {
        // Convenience constructors/tests share a bounded daemon executor. Production owns its instance.
        internal val shared by lazy { WorkerExecution() }
        private val deadline = ThreadLocal<Long>()

        fun remaining(maximum: Duration): Duration {
            val nanos = minOf(deadline.get()?.let { it - System.nanoTime() } ?: maximum.toNanos(), maximum.toNanos())
            // Millisecond-based SDKs interpret a truncated zero timeout as unlimited.
            if (nanos < TimeUnit.MILLISECONDS.toNanos(1) || Thread.currentThread().isInterrupted) throw ProcessingDeadlineExceeded()
            return Duration.ofNanos(nanos)
        }
        fun expired(): Boolean = Thread.currentThread().isInterrupted ||
            deadline.get()?.let { it - System.nanoTime() < TimeUnit.MILLISECONDS.toNanos(1) } == true
    }
}
