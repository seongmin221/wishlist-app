package app.analysis

import app.tasks.AnalysisTask
import app.tasks.TaskGateway
import app.tasks.TaskStatus
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

data class PendingRecoveryReport(
    val rescheduled: Int, val failed: Int, val rescheduleExhausted: Int,
    val cancelled: Int, val alive: Int, val lookupFailed: Int,
)

/**
 * Recovers PENDING jobs whose published task disappeared (retry exhaustion, an ACK after the delivery deadline,
 * task loss). Unpublished events belong to the outbox dispatcher and are not discovered here. The queue is asked
 * outside any transaction; only a confirmed MISSING task is acted on, after revalidating under owner -> item -> job locks.
 */
class PendingJobRecovery(
    private val dataSource: DataSource,
    private val tasks: TaskGateway,
    private val batchSize: Int = 50,
    private val staleSeconds: Long = 300,
    private val aliveRecheckSeconds: Long = 300,
    private val lookupRetrySeconds: Long = 60,
    private val maxReschedules: Int = 3,
) {
    init { require(batchSize in 1..1000 && maxReschedules >= 0) }

    fun recover(deadlineNanos: Long = Long.MAX_VALUE): PendingRecoveryReport {
        val outcomes = mutableMapOf<Outcome, Int>()
        for (candidate in discover()) {
            if (System.nanoTime() - deadlineNanos >= 0) break
            val outcome = try { inspect(candidate) } catch (cause: Exception) {
                if (cause is CancellationException || cause is InterruptedException) throw cause
                logger.error("Pending recovery failed jobId={} exceptionType={}", candidate.id, cause.javaClass.name)
                // A failing deferral (same DB outage) must not abort the rest of the batch.
                try { dataSource.deferRecoveryCheck(candidate.id, lookupRetrySeconds) } catch (deferral: Exception) {
                    if (deferral is CancellationException || deferral is InterruptedException) throw deferral
                    logger.error("Pending recovery deferral failed jobId={} exceptionType={}", candidate.id, deferral.javaClass.name)
                }
                Outcome.UNCHANGED
            }
            outcomes.merge(outcome, 1, Int::plus)
        }
        fun count(outcome: Outcome) = outcomes[outcome] ?: 0
        return PendingRecoveryReport(count(Outcome.RESCHEDULED), count(Outcome.FAILED), count(Outcome.RESCHEDULE_EXHAUSTED),
            count(Outcome.CANCELLED), count(Outcome.ALIVE), count(Outcome.LOOKUP_FAILED))
    }

    private fun inspect(candidate: Candidate): Outcome {
        if (candidate.outboxId != null) {
            val status = try {
                tasks.status(AnalysisTask(candidate.taskName!!, candidate.id, candidate.generation, candidate.eventType!!))
            } catch (cause: Exception) {
                if (cause is CancellationException || cause is InterruptedException) throw cause
                logger.warn("Task lookup failed jobId={} exceptionType={}", candidate.id, cause.javaClass.name)
                dataSource.deferRecoveryCheck(candidate.id, lookupRetrySeconds)
                return Outcome.LOOKUP_FAILED
            }
            if (status == TaskStatus.ALIVE) {
                dataSource.deferRecoveryCheck(candidate.id, aliveRecheckSeconds)
                return Outcome.ALIVE
            }
        }
        val outcome = dataSource.connection.use { c ->
            c.autoCommit = false
            try { resolveMissing(c, candidate).also { c.commit() } } catch (cause: SQLException) {
                c.rollback()
                // A concurrent recovery inserted the same task name first.
                if (cause.sqlState == UNIQUE_VIOLATION) Outcome.UNCHANGED else throw cause
            } catch (cause: Throwable) { c.rollback(); throw cause }
        }
        if (outcome == Outcome.LOCKED) dataSource.deferRecoveryCheck(candidate.id, lookupRetrySeconds)
        return if (outcome == Outcome.LOCKED) Outcome.UNCHANGED else outcome
    }

    private fun resolveMissing(c: Connection, candidate: Candidate): Outcome {
        if (!c.lockAnalysisOwner(candidate.itemId, skipLocked = true)) return Outcome.LOCKED
        val item = c.lockAnalysisItem(candidate.itemId, skipLocked = true) ?: return Outcome.LOCKED
        val job = c.lockAnalysisJob(candidate.id, skipLocked = true) ?: return Outcome.LOCKED
        val (updatedAt, recoverySeq) = c.prepareStatement("select updated_at,recovery_seq from analysis_jobs where id=?").use { s ->
            s.setObject(1, candidate.id); s.executeQuery().use { r -> check(r.next()); r.getTimestamp(1).toInstant() to r.getInt(2) }
        }
        val latestOutbox = c.prepareStatement("""select id from outbox_events where analysis_job_id=?
            order by created_at desc, id desc limit 1""").use { s ->
            s.setObject(1, candidate.id); s.executeQuery().use { r -> if (r.next()) r.getObject(1, UUID::class.java) else null }
        }
        // A claim, retry or recovery between discovery and these locks changes one of these; that progress wins.
        if (job.itemId != candidate.itemId || job.generation != candidate.generation || job.stage != candidate.stage ||
            updatedAt != candidate.updatedAt || latestOutbox != candidate.outboxId) return Outcome.UNCHANGED
        if (!item.canAnalyze(job.generation)) {
            c.transitionAnalysisJob(candidate.id, "CANCELLED")
            return Outcome.CANCELLED
        }
        if (!job.hasRetryBudget(c.analysisDatabaseTime())) {
            c.failExhausted(candidate.id, candidate.itemId)
            return Outcome.FAILED
        }
        if (recoverySeq >= maxReschedules) {
            // Tasks that keep vanishing before any claim (auth errors, start-up crashes) would otherwise loop forever.
            c.failExhausted(candidate.id, candidate.itemId)
            return Outcome.RESCHEDULE_EXHAUSTED
        }
        val browser = candidate.stage == "BROWSER_PENDING"
        val seq = c.prepareStatement("""update analysis_jobs set recovery_seq=recovery_seq+1,recovery_check_at=null,
            updated_at=clock_timestamp() where id=? returning recovery_seq""").use { s ->
            s.setObject(1, candidate.id); s.executeQuery().use { r -> check(r.next()); r.getInt(1) }
        }
        c.insertAnalysisOutbox(candidate.id, if (browser) AnalysisLane.BROWSER else AnalysisLane.GENERAL,
            "${if (browser) "browser" else "analysis"}-${candidate.id}-${candidate.generation}-pending-$seq")
        return Outcome.RESCHEDULED
    }

    private fun discover(): List<Candidate> = dataSource.connection.use { c ->
        c.prepareStatement("""
            select j.id, j.wishlist_item_id, j.generation, j.stage, j.updated_at, o.id outbox_id, o.task_name, o.event_type
            from analysis_jobs j
            left join lateral (select id, task_name, event_type, published_at from outbox_events
                               where analysis_job_id = j.id order by created_at desc, id desc limit 1) o on true
            where j.stage in ('GENERAL_PENDING','BROWSER_PENDING')
              and j.updated_at <= clock_timestamp() - make_interval(secs => ?)
              and (j.recovery_check_at is null or j.recovery_check_at <= clock_timestamp())
              and (o.id is null or o.published_at is not null)
            order by j.recovery_check_at nulls first, j.updated_at, j.id
            limit ?
        """.trimIndent()).use { s ->
            s.setDouble(1, staleSeconds.toDouble()); s.setInt(2, batchSize)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(Candidate(r.getObject("id", UUID::class.java), r.getObject("wishlist_item_id", UUID::class.java),
                        r.getInt("generation"), r.getString("stage"), r.getTimestamp("updated_at").toInstant(),
                        r.getObject("outbox_id", UUID::class.java), r.getString("task_name"), r.getString("event_type")))
                }
            }
        }
    }

    private enum class Outcome { RESCHEDULED, FAILED, RESCHEDULE_EXHAUSTED, CANCELLED, ALIVE, LOOKUP_FAILED, UNCHANGED, LOCKED }

    private data class Candidate(val id: UUID, val itemId: UUID, val generation: Int, val stage: String, val updatedAt: Instant,
        val outboxId: UUID?, val taskName: String?, val eventType: String?)

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        val logger = LoggerFactory.getLogger(PendingJobRecovery::class.java)
    }
}
