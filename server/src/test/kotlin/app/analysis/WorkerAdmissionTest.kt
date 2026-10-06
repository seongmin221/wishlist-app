package app.analysis

import app.browser.BrowserWorkerService
import app.browser.BrowserNavigationTimeout
import app.testutil.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkerAdmissionTest {
    @Test fun `expired queued deliveries never claim or consume attempts in either lane`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            val before = finishSnapshot(source, job)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val callers = Executors.newFixedThreadPool(3)
            WorkerExecution(timeoutMillis = 10000, processingMillis = 1000).use { execution ->
                try {
                    val blockers = (1..2).map { callers.submit<WorkerDisposition> {
                        execution.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); WorkerDisposition.ACKNOWLEDGE }
                    } }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val queued = callers.submit<WorkerDisposition> {
                        if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, execution) { ProcessingOutcome.Retryable }.runGeneral(job.jobId, 1)
                        else BrowserWorkerService(source, { throw BrowserNavigationTimeout() }, { _, _ -> error("must not classify") }, execution).runBrowser(job.jobId, 1)
                    }
                    val field = WorkerExecution::class.java.getDeclaredField("executor").apply { isAccessible = true }
                    val executor = field.get(execution) as ThreadPoolExecutor
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (executor.queue.size != 1 && System.nanoTime() < until) Thread.yield()
                    assertEquals(1, executor.queue.size)
                    // Waiting longer than the processing budget is the scenario under test.
                    Thread.sleep(1100)
                    release.countDown()
                    blockers.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(WorkerDisposition.RETRY, queued.get(5, TimeUnit.SECONDS))
                    assertEquals(before, finishSnapshot(source, job))
                } finally { release.countDown(); callers.shutdownNow() }
            }
        }
    }

    @Test fun `finish cancels only matching version-invalid execution even for stale outcome`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) for (outcome in listOf(ProcessingOutcome.Complete, ProcessingOutcome.Stale)) {
            val claim = newAnalysisClaim(source, lane)
            seedFinishResult(source, claim)
            analysisSql(source, "update wishlist_items set version=version+1,product_name='Edited',name_source='USER' where id='${claim.itemId}'")
            assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, outcome))
            assertEquals("CANCELLED", analysisScalar(source, "select stage from analysis_jobs where id='${claim.jobId}'"))
            assertEquals("FAILED_RETRYABLE", analysisScalar(source, "select analysis_status from wishlist_items where id='${claim.itemId}'"))
            assertEquals("Edited", analysisScalar(source, "select product_name from wishlist_items where id='${claim.itemId}'"))
            assertEquals("3", analysisScalar(source, "select version from wishlist_items where id='${claim.itemId}'"))
            val after = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
            AnalysisResultRepository(source).finish(claim, outcome)
            assertEquals(after, finishSnapshot(source, FinishJob(claim.itemId, claim.jobId)))
        }
    }

    @Test fun `protected missing category preserves user reason without blaming valid AI`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val claim = newAnalysisClaim(source, lane)
            analysisSql(source, "update wishlist_items set category_source='USER',category_missing_reason='CUSTOM_CATEGORY_DELETED',user_override_fields=array['CATEGORY'] where id='${claim.itemId}'")
            seedFinishResult(source, claim)
            AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
            assertEquals("PARTIAL", analysisScalar(source, "select analysis_status from wishlist_items where id='${claim.itemId}'"))
            assertNull(analysisScalar(source, "select category_id from wishlist_items where id='${claim.itemId}'"))
            assertNull(analysisScalar(source, "select analysis_failure_code from wishlist_items where id='${claim.itemId}'"))
            assertEquals("CUSTOM_CATEGORY_DELETED", analysisScalar(source, "select category_missing_reason from wishlist_items where id='${claim.itemId}'"))
            assertEquals("C026", analysisScalar(source, "select predicted_category_id from wishlist_items where id='${claim.itemId}'"))
        }
    }
}
