package app.analysis

import app.ai.ClassificationResult
import app.browser.BrowserWorkerService
import app.extraction.Metadata
import app.testutil.*
import java.math.BigDecimal
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GenerationBudgetTest {
    private fun stage(source: DataSource, job: FinishJob) = analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'")
    private fun status(source: DataSource, job: FinishJob) = analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'")
    private fun browserOutboxes(source: DataSource, job: FinishJob) =
        analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${job.jobId}' and event_type='BROWSER_ANALYSIS'")

    @Test fun `general then fallback allows two browser attempts and the generation ends after three in total`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        val results = AnalysisResultRepository(source)
        results.finish(claimJob(source, job.jobId), ProcessingOutcome.NeedsBrowser)
        assertEquals("BROWSER_PENDING", stage(source, job))
        assertEquals(WorkerDisposition.ACKNOWLEDGE, results.finish(claimJob(source, job.jobId, AnalysisLane.BROWSER), ProcessingOutcome.Retryable))
        assertEquals("BROWSER_PENDING", stage(source, job))
        assertEquals(WorkerDisposition.ACKNOWLEDGE, results.finish(claimJob(source, job.jobId, AnalysisLane.BROWSER), ProcessingOutcome.Retryable))
        assertEquals("FAILED", stage(source, job))
        assertEquals("FAILED_RETRYABLE", status(source, job))
        assertEquals("""[1, 2]""", analysisScalar(source, "select jsonb_build_array(attempt_count,browser_attempt_count)::text from analysis_jobs where id='${job.jobId}'"))
    }

    @Test fun `a pending browser job whose combined attempts are used is exhausted at claim`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.BROWSER)
        analysisSql(source, "update analysis_jobs set attempt_count=1,browser_attempt_count=2,first_attempt_at=clock_timestamp() where id='${job.jobId}'")
        assertEquals(ClaimResult.Exhausted, AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.BROWSER))
        assertEquals("FAILED_RETRYABLE", status(source, job))
    }

    @Test fun `needs browser on the third general attempt fails without a browser task`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        analysisSql(source, "update analysis_jobs set attempt_count=2,first_attempt_at=clock_timestamp() where id='${job.jobId}'")
        assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claimJob(source, job.jobId), ProcessingOutcome.NeedsBrowser))
        assertEquals("0", browserOutboxes(source, job))
        assertEquals("FAILED", stage(source, job))
        assertEquals("FAILED_RETRYABLE", status(source, job))
        assertEquals("2", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
    }

    @Test fun `thirty minutes count from the earliest attempt of either lane`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.BROWSER)
        analysisSql(source, "update analysis_jobs set attempt_count=1,first_attempt_at=clock_timestamp()-interval '31 minutes' where id='${job.jobId}'")
        assertEquals(ClaimResult.Exhausted, AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.BROWSER))
    }

    @Test fun `exhaustion at claim applies the page metadata already read`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        val claim = claimJob(source, job.jobId)
        AnalysisPendingResultRepository(source).saveMetadata(claim, Metadata("Read title", null, null, "https://example.com/p/1",
            "Brand", BigDecimal("1000"), "KRW", "Shop"))
        assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Retryable))
        analysisSql(source, "update analysis_jobs set first_attempt_at=clock_timestamp()-interval '31 minutes' where id='${job.jobId}'")
        assertEquals(ClaimResult.Exhausted, AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.GENERAL))
        assertEquals("FAILED_RETRYABLE", status(source, job))
        assertEquals("""["Read title", "Brand", 1000.0000, "KRW", "Shop", true]""", analysisScalar(source,
            "select jsonb_build_array(product_name,product_brand,product_price,product_currency,merchant_name,metadata_checked_at is not null)::text from wishlist_items where id='${job.itemId}'"))
    }

    @Test fun `running recovery keeps attempts and only a new claim increments them`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        claimJob(source, job.jobId)
        analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='${job.jobId}'")
        assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
        assertEquals("1", analysisScalar(source, "select attempt_count from analysis_jobs where id='${job.jobId}'"))
        claimJob(source, job.jobId)
        assertEquals("2", analysisScalar(source, "select attempt_count from analysis_jobs where id='${job.jobId}'"))
    }

    @Test fun `retryable finish acknowledges and schedules exponential backoff`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            for (expected in listOf(10, 20)) {
                assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claimJob(source, job.jobId, lane), ProcessingOutcome.Retryable))
                val delay = analysisScalar(source, """select extract(epoch from not_before-created_at)::int from outbox_events
                    where analysis_job_id='${job.jobId}' order by created_at desc, id desc limit 1""")!!.toInt()
                assertEquals(true, delay in expected - 1..expected + 1, "$lane $expected $delay")
            }
        }
    }

    @Test fun `retry backoff doubles and is capped`() {
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L, 320L, 600L, 600L), (1..8).map(::retryBackoffSeconds))
        assertEquals(10L, retryBackoffSeconds(0))
    }

    @Test fun `exhausted browser retry is acknowledged`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.BROWSER)
        analysisSql(source, "update analysis_jobs set attempt_count=1,browser_attempt_count=1,first_attempt_at=clock_timestamp() where id='${job.jobId}'")
        val result = BrowserWorkerService(source, { error("browser runtime exited") }, { _, _ -> error("must not classify") }).runBrowser(job.jobId, 1)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, result)
        assertEquals("FAILED_RETRYABLE", status(source, job))
        assertNull(analysisScalar(source, "select execution_token from analysis_jobs where id='${job.jobId}'"))
    }
}
