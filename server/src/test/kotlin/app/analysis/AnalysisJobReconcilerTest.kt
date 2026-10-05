package app.analysis

import app.ai.ClassificationResult
import app.budget.LlmBudgetService
import app.budget.ReserveResult
import app.extraction.Metadata
import app.testutil.*
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class AnalysisJobReconcilerTest {
    @Test fun `expired lease requeues each lane and old claim cannot write after new claim`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val old = newAnalysisClaim(source, lane)
            seedFinishResult(source, old)
            expire(source, old)
            val reconciler = AnalysisJobReconciler(source)
            assertEquals(1, reconciler.reconcileExpired())
            assertCleared(source, old, "${lane.name}_PENDING")
            assertEquals("1", scalar(source, old, "version", item = true))
            assertEquals("1", scalar(source, old, if (lane == AnalysisLane.GENERAL) "attempt_count" else "browser_attempt_count"))
            val next = claimJob(source, old.jobId, lane)
            assertNotEquals(old.executionToken, next.executionToken)
            val before = finishSnapshot(source, FinishJob(old.itemId, old.jobId))
            val pending = AnalysisPendingResultRepository(source)
            assertFalse(pending.saveMetadata(old, Metadata("stale", null, null, "https://example.com/stale")))
            assertFalse(pending.saveAssignment(old, ClassificationResult.Assigned("STALE", null)))
            assertFalse(pending.saveFailure(old, "ACCESS_DENIED"))
            for (outcome in listOf(ProcessingOutcome.Complete, ProcessingOutcome.Partial, ProcessingOutcome.Terminal, ProcessingOutcome.Retryable, ProcessingOutcome.NeedsBrowser)) {
                assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(old, outcome))
                assertEquals(before, finishSnapshot(source, FinishJob(old.itemId, old.jobId)))
            }
            seedFinishResult(source, next)
            assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(next, ProcessingOutcome.Complete))
            assertEquals("READY", scalar(source, next, "analysis_status", item = true))
            assertEquals("2", scalar(source, next, "version", item = true))
            assertEquals(0, reconciler.reconcileExpired())
        }
    }

    @Test fun `invalid item cancels expired execution without changing item or creating outbox`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) for (change in listOf("lifecycle_status='ARCHIVED'", "lifecycle_status='DELETED'", "current_generation=2", "version=version+1", "manual_completion_at=clock_timestamp()", "analysis_status='READY'")) {
            val claim = newAnalysisClaim(source, lane)
            expire(source, claim)
            analysisSql(source, "update wishlist_items set $change where id='${claim.itemId}'")
            val before = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
            assertEquals(1, AnalysisJobReconciler(source).reconcileExpired(), "$lane $change")
            assertCleared(source, claim, "CANCELLED")
            val after = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
            assertEquals(before[0], after[0], "$lane $change item")
            assertEquals(before[2], after[2], "$lane $change outbox")
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
        }
    }

    @Test fun `expired limits fail once using lane attempts and database deadline`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) for (limit in listOf("attempts", "deadline")) {
            val claim = newAnalysisClaim(source, lane)
            expire(source, claim)
            val column = if (limit == "attempts") {
                if (lane == AnalysisLane.GENERAL) "attempt_count=3" else "browser_attempt_count=3"
            } else if (lane == AnalysisLane.GENERAL) "first_attempt_at=clock_timestamp()-interval '30 minutes'" else "first_browser_attempt_at=clock_timestamp()-interval '30 minutes'"
            analysisSql(source, "update analysis_jobs set $column where id='${claim.jobId}'")
            val before = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
            assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
            assertCleared(source, claim, "FAILED")
            assertEquals("FAILED_RETRYABLE", scalar(source, claim, "analysis_status", item = true))
            assertEquals("2", scalar(source, claim, "version", item = true))
            assertEquals(before[2], finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))[2])
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
            assertEquals("2", scalar(source, claim, "version", item = true))
        }
    }

    @Test fun `live lease ignores old updated timestamp and nonrunning jobs`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val claim = newAnalysisClaim(source, lane)
            analysisSql(source, "update analysis_jobs set updated_at=clock_timestamp()-interval '1 hour' where id='${claim.jobId}'")
            val before = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
            assertEquals(before, finishSnapshot(source, FinishJob(claim.itemId, claim.jobId)))
            analysisSql(source, "update analysis_jobs set stage='${lane.name}_PENDING',execution_token=null,lease_until=null,claimed_item_version=null where id='${claim.jobId}'")
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
        }
    }

    @Test fun `legacy running without execution fields can recover in both lanes`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) for (migrated in listOf(false, true)) {
            val job = newFinishJob(source, lane)
            analysisSql(source, "update analysis_jobs set stage='${lane.name}_RUNNING',attempt_count=1,browser_attempt_count=1,lease_until=${if (migrated) "clock_timestamp()-interval '1 second'" else "null"} where id='${job.jobId}'")
            assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
            assertEquals("${lane.name}_PENDING", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            val claim = claimJob(source, job.jobId, lane)
            seedFinishResult(source, claim)
            AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
            assertEquals("READY", scalar(source, claim, "analysis_status", item = true))
        }
    }

    @Test fun `two reconcilers emit one unique recovery event per execution`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            var claim = newAnalysisClaim(source, lane)
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(2) { round ->
                    expire(source, claim)
                    val barrier = CyclicBarrier(2)
                    val futures = (1..2).map { pool.submit<Int> { barrier.await(5, TimeUnit.SECONDS); AnalysisJobReconciler(source).reconcileExpired() } }
                    assertEquals(1, futures.sumOf { it.get(10, TimeUnit.SECONDS) })
                    assertEquals("${round + 1}", analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${claim.jobId}' and task_name like '%-recovery-%'"))
                    assertEquals("${round + 1}", analysisScalar(source, "select count(distinct task_name) from outbox_events where analysis_job_id='${claim.jobId}' and task_name like '%-recovery-%'"))
                    assertEquals("1", scalar(source, claim, "version", item = true))
                    if (round == 0) claim = claimJob(source, claim.jobId, lane)
                }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `locked item or job is skipped and renewed lease is rechecked`() = withAnalysisDatabase { source ->
        for (row in listOf("wishlist_items", "analysis_jobs")) {
            val claim = newAnalysisClaim(source)
            expire(source, claim)
            source.connection.use { lock ->
                lock.autoCommit = false
                val id = if (row == "wishlist_items") claim.itemId else claim.jobId
                lock.createStatement().use { it.executeQuery("select id from $row where id='$id' for update").close() }
                val pool = Executors.newSingleThreadExecutor()
                try {
                    assertEquals(0, pool.submit<Int> { AnalysisJobReconciler(source).reconcileExpired() }.get(3, TimeUnit.SECONDS))
                    lock.createStatement().use { it.executeUpdate("update analysis_jobs set lease_until=clock_timestamp()+interval '120 seconds',execution_token='${UUID.randomUUID()}' where id='${claim.jobId}'") }
                    lock.commit()
                } finally { lock.rollback(); pool.shutdownNow() }
            }
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
            assertEquals("GENERAL_RUNNING", scalar(source, claim, "stage"))
        }
    }

    @Test fun `discovered execution replaced before locks is skipped`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        expire(source, claim)
        var afterReplacement: List<String?> = emptyList()
        val result = pausedAnalysisCall({ pause ->
            val observed = pauseDiscovery(source, pause)
            AnalysisJobReconciler(observed).reconcileExpired()
        }, {
            analysisSql(source, "update analysis_jobs set execution_token='${UUID.randomUUID()}',lease_until=clock_timestamp()-interval '2 seconds' where id='${claim.jobId}'")
            afterReplacement = finishSnapshot(source, FinishJob(claim.itemId, claim.jobId))
        })
        assertEquals(0, result)
        assertEquals(afterReplacement, finishSnapshot(source, FinishJob(claim.itemId, claim.jobId)))
        assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
    }

    @Test fun `finish after expired candidate discovery wins without duplicate version or outbox`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val claim = newAnalysisClaim(source, lane)
            expire(source, claim)
            val recovered = pausedAnalysisCall({ pause ->
                AnalysisJobReconciler(pauseDiscovery(source, pause)).reconcileExpired()
            }, {
                // The scanner has already discovered the expired execution without taking locks.
                analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()+interval '120 seconds' where id='${claim.jobId}'")
                seedFinishResult(source, claim)
                assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete))
            })
            assertEquals(0, recovered)
            assertEquals("2", scalar(source, claim, "version", item = true))
            assertCleared(source, claim, "COMPLETE")
            assertEquals("0", analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${claim.jobId}' and task_name like '%-recovery-%'"))
        }
    }

    @Test fun `recovery leaves actual budget settlement and reservations unchanged`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val settled = assertIs<ReserveResult.Reserved>(budget.reserveBeforeCall(claim, UUID.randomUUID())).reservation
        budget.markInFlight(settled.id)
        budget.settle(settled.id, 500, 20)
        assertIs<ReserveResult.Reserved>(budget.reserveBeforeCall(claim, UUID.randomUUID()))
        fun snapshot() = listOf(analysisScalar(source, "select jsonb_agg(to_jsonb(r) order by id)::text from llm_budget_reservations r"),
            analysisScalar(source, "select jsonb_agg(to_jsonb(w) order by id)::text from llm_budget_windows w"))
        val before = snapshot()
        expire(source, claim)
        assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
        assertEquals(before, snapshot())
        assertEquals(124L, budget.windowTotals("DAILY").settled)
        assertEquals(496L, budget.windowTotals("DAILY").reserved)
    }

    private fun pauseDiscovery(source: DataSource, pause: () -> Unit): DataSource = object : DataSource by source {
        override fun getConnection(): java.sql.Connection {
            val c = source.connection
            return object : java.sql.Connection by c {
                override fun prepareStatement(sql: String): java.sql.PreparedStatement {
                    val statement = c.prepareStatement(sql)
                    if (!sql.trimStart().startsWith("select") || !sql.contains("GENERAL_RUNNING")) return statement
                    return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, method, args ->
                        val value = try { method.invoke(statement, *(args ?: emptyArray())) }
                            catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                        if (method.name != "executeQuery") value else {
                            val rows = value as java.sql.ResultSet
                            var discovered = 0
                            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.ResultSet::class.java)) { _, rowMethod, rowArgs ->
                                val rowValue = try { rowMethod.invoke(rows, *(rowArgs ?: emptyArray())) }
                                    catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                                if (rowMethod.name == "next" && rowValue == true) discovered++
                                if (rowMethod.name == "close") {
                                    check(discovered > 0) { "race must observe an expired candidate before pausing" }
                                    pause()
                                }
                                rowValue
                            }
                        }
                    } as java.sql.PreparedStatement
                }
            }
        }
    }

    private fun expire(source: DataSource, claim: AnalysisClaim) = analysisSql(source,
        "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='${claim.jobId}'")

    private fun scalar(source: DataSource, claim: AnalysisClaim, column: String, item: Boolean = false) = analysisScalar(source,
        "select $column from ${if (item) "wishlist_items" else "analysis_jobs"} where id='${if (item) claim.itemId else claim.jobId}'")

    private fun assertCleared(source: DataSource, claim: AnalysisClaim, stage: String) {
        assertEquals(stage, scalar(source, claim, "stage"))
        for (column in listOf("execution_token", "lease_until", "claimed_item_version")) assertNull(scalar(source, claim, column), column)
    }
}
