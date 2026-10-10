package app.analysis

import app.tasks.AnalysisTask
import app.tasks.TaskGateway
import app.tasks.TaskStatus
import app.testutil.*
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingJobRecoveryTest {
    /** A PENDING job whose only outbox event was published `age` ago; the queue no longer holds the task unless kept. */
    private fun publishedPending(source: DataSource, lane: AnalysisLane = AnalysisLane.GENERAL, age: String = "5 minutes 1 second"): FinishJob {
        val job = newFinishJob(source, lane)
        analysisSql(source, "update outbox_events set published_at=clock_timestamp() where analysis_job_id='${job.jobId}'")
        analysisSql(source, "update analysis_jobs set updated_at=clock_timestamp()-interval '$age' where id='${job.jobId}'")
        return job
    }

    private fun latestTask(source: DataSource, job: FinishJob): AnalysisTask {
        val name = analysisScalar(source, "select task_name from outbox_events where analysis_job_id='${job.jobId}' order by created_at desc, id desc limit 1")!!
        return AnalysisTask(name, job.jobId, 1, "GENERAL_ANALYSIS")
    }

    private fun outboxes(source: DataSource, job: FinishJob) = analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${job.jobId}'")
    private fun job(source: DataSource, job: FinishJob, column: String) = analysisScalar(source, "select $column from analysis_jobs where id='${job.jobId}'")
    private fun item(source: DataSource, job: FinishJob, column: String) = analysisScalar(source, "select $column from wishlist_items where id='${job.itemId}'")
    private fun checkInSeconds(source: DataSource, job: FinishJob) =
        job(source, job, "extract(epoch from recovery_check_at-clock_timestamp())::int")!!.toInt()

    @Test fun `pending younger than five minutes is not inspected`() = withAnalysisDatabase { source ->
        publishedPending(source, age = "4 minutes 59 seconds")
        val queue = InMemoryTaskQueue().apply { failLookups = true }
        assertEquals(PendingRecoveryReport(0, 0, 0, 0, 0, 0), PendingJobRecovery(source, queue).recover())
    }

    @Test fun `unpublished backlog does not crowd out a lost published task`() = withAnalysisDatabase { source ->
        repeat(60) {
            val backlog = newFinishJob(source, AnalysisLane.GENERAL)
            analysisSql(source, "update analysis_jobs set updated_at=clock_timestamp()-interval '1 hour' where id='${backlog.jobId}'")
        }
        val lost = publishedPending(source)
        val report = PendingJobRecovery(source, InMemoryTaskQueue(), batchSize = 50).recover()
        assertEquals(1, report.rescheduled)
        assertEquals("1", job(source, lost, "recovery_seq"))
    }

    @Test fun `alive task and lookup failure change nothing but the next check time`() = withAnalysisDatabase { source ->
        val alive = publishedPending(source)
        val queue = InMemoryTaskQueue().apply { create(latestTask(source, alive)) }
        assertEquals(1, PendingJobRecovery(source, queue).recover().alive)
        assertEquals(true, checkInSeconds(source, alive) in 290..300)
        assertEquals("1", outboxes(source, alive))

        val unknown = publishedPending(source)
        assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue().apply { failLookups = true }).recover().lookupFailed)
        assertEquals(true, checkInSeconds(source, unknown) in 50..60)
        assertEquals("PROCESSING", item(source, unknown, "analysis_status"))
        assertEquals("1", outboxes(source, unknown))
    }

    @Test fun `missing task is rescheduled under a new name without spending attempts`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val lost = publishedPending(source, lane)
            val before = listOf(job(source, lost, "stage"), job(source, lost, "attempt_count"), job(source, lost, "browser_attempt_count"), item(source, lost, "version"))
            assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue()).recover().rescheduled)
            val prefix = if (lane == AnalysisLane.BROWSER) "browser" else "analysis"
            assertEquals("$prefix-${lost.jobId}-1-pending-1", latestTask(source, lost).name)
            assertEquals(before, listOf(job(source, lost, "stage"), job(source, lost, "attempt_count"), job(source, lost, "browser_attempt_count"), item(source, lost, "version")))
            assertEquals("1", job(source, lost, "recovery_seq"))
            analysisSql(source, "update outbox_events set published_at=clock_timestamp() where analysis_job_id='${lost.jobId}'")
        }
    }

    @Test fun `pending job without any outbox is rescheduled`() = withAnalysisDatabase { source ->
        val orphan = publishedPending(source)
        analysisSql(source, "delete from outbox_events where analysis_job_id='${orphan.jobId}'")
        assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue()).recover().rescheduled)
        assertEquals("1", outboxes(source, orphan))
    }

    @Test fun `reschedules are capped per job even when claims happened in between`() = withAnalysisDatabase { source ->
        val lost = publishedPending(source)
        analysisSql(source, "update analysis_jobs set recovery_seq=3,attempt_count=1,first_attempt_at=clock_timestamp() where id='${lost.jobId}'")
        assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue()).recover().rescheduleExhausted)
        assertEquals("FAILED", job(source, lost, "stage"))
        assertEquals("FAILED_RETRYABLE", item(source, lost, "analysis_status"))
        assertEquals("1", outboxes(source, lost))
    }

    @Test fun `exhausted generation budget fails the item`() = withAnalysisDatabase { source ->
        val lost = publishedPending(source)
        analysisSql(source, "update analysis_jobs set attempt_count=3,first_attempt_at=clock_timestamp() where id='${lost.jobId}'")
        assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue()).recover().failed)
        assertEquals("FAILED_RETRYABLE", item(source, lost, "analysis_status"))
    }

    @Test fun `inactive or superseded items only cancel the job`() = withAnalysisDatabase { source ->
        for (change in listOf("lifecycle_status='DELETED'", "lifecycle_status='ARCHIVED'", "manual_completion_at=clock_timestamp()", "current_generation=2")) {
            val lost = publishedPending(source)
            analysisSql(source, "update wishlist_items set $change where id='${lost.itemId}'")
            val version = item(source, lost, "version")
            assertEquals(1, PendingJobRecovery(source, InMemoryTaskQueue()).recover().cancelled, change)
            assertEquals("CANCELLED", job(source, lost, "stage"))
            assertEquals(version, item(source, lost, "version"))
            assertEquals("1", outboxes(source, lost))
        }
    }

    @Test fun `a claim during the queue lookup wins`() = withAnalysisDatabase { source ->
        val lost = publishedPending(source)
        val report = pausedAnalysisCall({ pause ->
            PendingJobRecovery(source, object : TaskGateway {
                override fun create(task: AnalysisTask) = error("not used")
                override fun status(task: AnalysisTask): TaskStatus { pause(); return TaskStatus.MISSING }
            }).recover()
        }, { claimJob(source, lost.jobId) })
        assertEquals(PendingRecoveryReport(0, 0, 0, 0, 0, 0), report)
        assertEquals("GENERAL_RUNNING", job(source, lost, "stage"))
        assertEquals("1", outboxes(source, lost))
    }

    @Test fun `a late retryable finish during the lookup leaves only its own retry outbox`() = withAnalysisDatabase { source ->
        val lost = publishedPending(source)
        pausedAnalysisCall({ pause ->
            PendingJobRecovery(source, object : TaskGateway {
                override fun create(task: AnalysisTask) = error("not used")
                override fun status(task: AnalysisTask): TaskStatus { pause(); return TaskStatus.MISSING }
            }).recover()
        }, { AnalysisResultRepository(source).finish(claimJob(source, lost.jobId), ProcessingOutcome.Retryable) })
        assertEquals("2", outboxes(source, lost))
        assertEquals("0", job(source, lost, "recovery_seq"))
    }

    @Test fun `concurrent recoveries reschedule a lost task once`() = withAnalysisDatabase { source ->
        val lost = publishedPending(source)
        val barrier = CyclicBarrier(2)
        val gateway = object : TaskGateway {
            override fun create(task: AnalysisTask) = error("not used")
            override fun status(task: AnalysisTask): TaskStatus { barrier.await(10, TimeUnit.SECONDS); return TaskStatus.MISSING }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reports = List(2) { pool.submit<PendingRecoveryReport> { PendingJobRecovery(source, gateway).recover() } }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, reports.sumOf { it.rescheduled })
        } finally { pool.shutdownNow() }
        assertEquals("2", outboxes(source, lost))
        assertEquals("1", job(source, lost, "recovery_seq"))
    }
}
