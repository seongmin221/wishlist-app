package app.analysis

import app.DatabaseFactory
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import app.testutil.PostgresTestContainer
import org.testcontainers.containers.PostgreSQLContainer

import app.testutil.*
import app.analysis.AnalysisLane

class GeneralWorkerServiceTest {
    @Test fun `both worker lanes enforce response deadline and interrupted processing retries durably`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            val before = analysisScalar(source, "select count(*) from outbox_events")!!.toInt()
            WorkerExecution(timeoutMillis = 200, processingMillis = 150).use { execution ->
                val block: () -> ProcessingOutcome = {
                    try { java.util.concurrent.CountDownLatch(1).await() } catch (_: InterruptedException) { }
                    ProcessingOutcome.Complete
                }
                val result = if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, execution) { block() }.runGeneral(job.jobId, 1)
                    else app.browser.BrowserWorkerService(source, { block(); null }, { _, _ -> error("must not classify") }, execution).runBrowser(job.jobId, 1)
                assertEquals(WorkerDisposition.RETRY, result)
                val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'") == "${lane.name}_RUNNING" && System.nanoTime() < until) Thread.yield()
                assertEquals("${lane.name}_PENDING", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
                assertEquals(before + 1, analysisScalar(source, "select count(*) from outbox_events")!!.toInt())
                assertEquals("PROCESSING", analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
            }
        }
    }

    @Test fun `retry persists new outbox even when duplicate delivery already acknowledged running job`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            val claim = claimJob(source, job.jobId, lane)
            val before = analysisScalar(source, "select count(*) from outbox_events")!!.toInt()
            assertEquals(ClaimResult.Ignored, AnalysisClaimRepository(source).claim(job.jobId, 1, lane))
            assertEquals(WorkerDisposition.RETRY, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Retryable))
            assertEquals(before + 1, analysisScalar(source, "select count(*) from outbox_events")!!.toInt())
            assertEquals("${lane.name}_PENDING", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Retryable))
            assertEquals(before + 1, analysisScalar(source, "select count(*) from outbox_events")!!.toInt())
        }
    }

    @Test fun `user edit holding item lock wins finish race without inverse job locking`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        val processing = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val finishConnected = java.util.concurrent.CountDownLatch(1)
        val finishing = java.util.concurrent.atomic.AtomicBoolean(false)
        val backend = java.util.concurrent.atomic.AtomicInteger()
        val observedSource = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                val c = source.connection
                if (finishing.get()) {
                    c.createStatement().use { s ->
                        s.executeQuery("select pg_backend_pid()").use { r -> check(r.next()); backend.set(r.getInt(1)) }
                    }
                    finishConnected.countDown()
                }
                return c
            }
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<WorkerDisposition> {
                GeneralWorkerService(observedSource) { claim ->
                    seedFinishResult(source, claim)
                    processing.countDown()
                    check(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    finishing.set(true)
                    ProcessingOutcome.Complete
                }.runGeneral(job.jobId, 1)
            }
            kotlin.test.assertTrue(processing.await(10, java.util.concurrent.TimeUnit.SECONDS))
            source.connection.use { edit ->
                edit.autoCommit = false
                try {
                    edit.createStatement().use { s ->
                        s.execute("set local statement_timeout='5s'")
                        s.executeUpdate("update wishlist_items set product_name='user won',name_source='USER',version=version+1 where id='${job.itemId}'")
                    }
                    proceed.countDown()
                    kotlin.test.assertTrue(finishConnected.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (System.nanoTime() < deadline) {
                        if (analysisScalar(source, "select wait_event_type from pg_stat_activity where pid=${backend.get()}") == "Lock") { blocked = true; break }
                    }
                    kotlin.test.assertTrue(blocked, "finish must wait for the item transaction")
                    // A job-first finish would hold this row and deadlock against our item lock.
                    edit.createStatement().use { it.executeUpdate("update analysis_jobs set stage='CANCELLED' where id='${job.jobId}'") }
                    edit.commit()
                } catch (cause: Throwable) { edit.rollback(); throw cause }
            }
            val afterEdit = finishSnapshot(source, job)
            assertEquals(WorkerDisposition.ACKNOWLEDGE, future.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(afterEdit, finishSnapshot(source, job))
            assertEquals("user won", analysisScalar(source, "select product_name from wishlist_items where id='${job.itemId}'"))
            assertEquals("2", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun `failure and partial preserve existing metadata in both lanes`() = withAnalysisDatabase { source ->
        assertIncompleteFinishMetadata(source)
    }

    @Test fun `missing categories map failure reasons and cannot become ready`() = withAnalysisDatabase { source ->
        for ((code, reason) in listOf("AI_ABSTAINED" to "AI_ABSTAINED", "AI_UNUSABLE_RESPONSE" to "AI_RESPONSE_UNUSABLE",
            "AI_INVALID_CANDIDATE" to "AI_RESPONSE_UNUSABLE", "AI_USAGE_OUT_OF_RANGE" to "AI_RESPONSE_UNUSABLE", "ACCESS_DENIED" to "EXTRACTION_UNRESOLVED")) {
            val job = newFinishJob(source, AnalysisLane.GENERAL)
            GeneralWorkerService(source) { claim ->
                AnalysisPendingResultRepository(source).saveFailure(claim, app.wishlist.AnalysisFailureCode.valueOf(code))
                ProcessingOutcome.Partial
            }.runGeneral(job.jobId, 1)
            assertEquals(reason, analysisScalar(source, "select category_missing_reason from wishlist_items where id='${job.itemId}'"))
            assertEquals("NOT_REQUIRED", analysisScalar(source, "select review_status from wishlist_items where id='${job.itemId}'"))
        }
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source) { ProcessingOutcome.Complete }.runGeneral(job.jobId, 1))
        assertEquals("PARTIAL", analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
        assertEquals("PARTIAL", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
        assertEquals("AI_INVALID_CANDIDATE", analysisScalar(source, "select analysis_failure_code from wishlist_items where id='${job.itemId}'"))
        kotlin.test.assertNull(analysisScalar(source, "select execution_token from analysis_jobs where id='${job.jobId}'"))
    }

    @Test fun `cancellation propagates without finishing either lane`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source) { throw kotlinx.coroutines.CancellationException("cancel") }.runGeneral(job.jobId, 1)
                else app.browser.BrowserWorkerService(source, { throw kotlinx.coroutines.CancellationException("cancel") }, { _, _ -> error("must not classify") }).runBrowser(job.jobId, 1)
            }
            assertEquals("${lane.name}_RUNNING", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            assertEquals("1", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        }
    }

    @Test fun `AI purpose with user category requires review in both lanes`() = withAnalysisDatabase { source ->
        assertPurposeOnlyReview(source)
    }

    @Test fun `general stale claims preserve fields and cancel version-invalid current executions`() = withAnalysisDatabase { source ->
        assertStaleFinishMatrix(source, AnalysisLane.GENERAL)
    }

    @Test fun `general final outcomes update version once and invalidate execution`() = withAnalysisDatabase { source ->
        assertNormalFinishMatrix(source, AnalysisLane.GENERAL)
    }

    @Test fun `general results preserve user and override values and finalized review`() = withAnalysisDatabase { source ->
        assertProtectedFinishMatrix(source, AnalysisLane.GENERAL)
    }

    @Test
    fun `duplicate and deleted job are acknowledged without processing`() = withJob { database, jobId, itemId ->
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { ProcessingOutcome.Retryable }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 2))
        database.createConnection("").use { connection ->
            connection.prepareStatement("update wishlist_items set lifecycle_status='DELETED' where id=?").use {
                it.setObject(1, itemId)
                it.executeUpdate()
            }
        }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(0, attempts(database, jobId))
    }

    @Test
    fun `third retryable attempt becomes failed retryable`() = withJob { database, jobId, _ ->
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { ProcessingOutcome.Retryable }
        repeat(2) { assertEquals(WorkerDisposition.RETRY, worker.runGeneral(jobId, 1)) }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(3, attempts(database, jobId))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("FAILED_RETRYABLE", rows.getString(1))
            }
        }
    }

    @Test
    fun `expired running job is requeued with a new outbox task`() = withJob { database, jobId, _ ->
        database.createConnection("").use { connection ->
            connection.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING', attempt_count=1, first_attempt_at=now()-interval '2 minutes', lease_until=clock_timestamp()-interval '1 second' where id=?").use {
                it.setObject(1, jobId)
                it.executeUpdate()
            }
        }
        val reconciler = AnalysisJobReconciler(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))

        assertEquals(1, reconciler.reconcileExpired())
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select stage from analysis_jobs").use { rows ->
                rows.next()
                assertEquals("GENERAL_PENDING", rows.getString(1))
            }
            connection.createStatement().executeQuery("select count(*) from outbox_events").use { rows ->
                rows.next()
                assertEquals(2, rows.getInt(1))
            }
        }
    }

    @Test
    fun `expired thirty minute deadline prevents another attempt`() = withJob { database, jobId, _ ->
        database.createConnection("").use { connection ->
            connection.prepareStatement("update analysis_jobs set attempt_count=1, first_attempt_at=now()-interval '31 minutes' where id=?").use {
                it.setObject(1, jobId)
                it.executeUpdate()
            }
        }
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { error("must not run") }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(1, attempts(database, jobId))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("FAILED_RETRYABLE", rows.getString(1))
            }
        }
    }

    @Test
    fun `claim revoked during processing cannot mark item ready`() = withJob { database, jobId, _ ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        val worker = GeneralWorkerService(source) { id ->
            source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='CANCELLED' where id=?").use { s -> s.setObject(1,id.jobId); s.executeUpdate() } }
            ProcessingOutcome.Complete
        }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select analysis_status from wishlist_items").use { r -> r.next(); assertEquals("PROCESSING", r.getString(1)) } }
    }

    private fun withJob(block: (PostgreSQLContainer<*>, UUID, UUID) -> Unit) {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val item = CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
                .create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item") as CreateResult.Created
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
            block(database, jobId, item.itemId)
        }
    }

    private fun attempts(database: PostgreSQLContainer<*>, jobId: UUID): Int = database.createConnection("").use { connection ->
        connection.prepareStatement("select attempt_count from analysis_jobs where id=?").use {
            it.setObject(1, jobId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
