package app.analysis

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkerExecutionTest {
    @Test fun `sub millisecond network budget cannot become unlimited timeout`() {
        assertFailsWith<ProcessingDeadlineExceeded> {
            WorkerExecution.remaining(Duration.ofNanos(999_999))
        }
        assertEquals(Duration.ofMillis(1), WorkerExecution.remaining(Duration.ofMillis(1)))
    }
    @Test fun `worker response deadline precedes task deadline and lease`() {
        assertTrue(AnalysisTiming.PROCESSING_SECONDS < AnalysisTiming.WORKER_SECONDS)
        assertTrue(AnalysisTiming.WORKER_SECONDS < AnalysisTiming.TASK_SECONDS)
        assertTrue(AnalysisTiming.TASK_SECONDS < AnalysisTiming.LEASE_SECONDS)
    }

    @Test fun `timeout interrupts work and returns retry without waiting for late callback`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        WorkerExecution(timeoutMillis = 100, processingMillis = 80).use { execution ->
            val started = System.nanoTime()
            assertEquals(WorkerDisposition.RETRY, execution.run {
                entered.countDown()
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
                WorkerDisposition.ACKNOWLEDGE
            })
            assertEquals(0L, entered.count)
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        }
    }

    @Test fun `network phases share remaining processing time rather than reset their budgets`() {
        WorkerExecution(timeoutMillis = 1000, processingMillis = 400).use { execution ->
            assertEquals(WorkerDisposition.ACKNOWLEDGE, execution.run {
                val first = WorkerExecution.remaining(Duration.ofSeconds(70))
                Thread.sleep(30)
                val second = WorkerExecution.remaining(Duration.ofSeconds(20))
                assertTrue(first.toMillis() <= 400)
                assertTrue(second < first)
                WorkerDisposition.ACKNOWLEDGE
            })
        }
        assertEquals(Duration.ofSeconds(20), WorkerExecution.remaining(Duration.ofSeconds(20)))
    }

    @Test fun `cancellation remains cancellation and closed execution rejects work`() {
        val execution = WorkerExecution()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            execution.run { throw kotlinx.coroutines.CancellationException("cancel") }
        }
        execution.close()
        assertEquals(WorkerDisposition.RETRY, execution.run { error("closed executor must not invoke callback") })
    }
}
