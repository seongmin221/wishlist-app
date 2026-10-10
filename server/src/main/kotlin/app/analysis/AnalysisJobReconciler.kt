package app.analysis

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class AnalysisJobReconciler(
    private val dataSource: DataSource,
    private val batchSize: Int = 100,
    private val onFailure: (UUID, Exception) -> Unit = { id, cause ->
        LoggerFactory.getLogger(AnalysisJobReconciler::class.java).error("Analysis recovery failed jobId={} exceptionType={}", id, cause.javaClass.name)
    },
) {
    init { require(batchSize in 1..1000) }
    fun reconcileExpired(deadlineNanos: Long = Long.MAX_VALUE): Int {
        // Discovery has no row locks. Each candidate is rechecked under item -> job locks.
        val candidates = dataSource.connection.use { connection ->
            connection.prepareStatement("""
                select id,wishlist_item_id,generation,stage,execution_token,lease_until,claimed_item_version
                from analysis_jobs where stage in ('GENERAL_RUNNING','BROWSER_RUNNING')
                and (lease_until<=clock_timestamp() or (execution_token is null and lease_until is null and claimed_item_version is null))
                and (recovery_check_at is null or recovery_check_at<=clock_timestamp())
                order by recovery_check_at nulls first,lease_until,id limit ?
            """.trimIndent()).use { statement ->
                statement.setInt(1, batchSize)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Candidate(
                            rows.getObject("id", UUID::class.java), rows.getObject("wishlist_item_id", UUID::class.java),
                            rows.getInt("generation"), rows.getString("stage"), rows.getObject("execution_token", UUID::class.java),
                            rows.getTimestamp("lease_until")?.toInstant(), rows.getObject("claimed_item_version") as Int?,
                        ))
                    }
                }
            }
        }
        var recoveredCount = 0
        for (candidate in candidates) {
            // Remaining candidates stay due and are picked up by the next maintenance run.
            if (System.nanoTime() - deadlineNanos >= 0) break
            var deferred = false
            val recovered = try { dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val changed = recoverLocked(connection, candidate)
                    connection.commit()
                    deferred = changed == null
                    changed == true
                } catch (cause: Throwable) {
                    connection.rollback()
                    throw cause
                }
            } } catch (cause: Exception) {
                if (cause is CancellationException || cause is InterruptedException) throw cause
                onFailure(candidate.id, cause)
                deferred = true
                false
            }
            // Locked or failed candidates move behind later ones for a minute; a raced one is simply re-read next scan.
            if (deferred) try { dataSource.deferRecoveryCheck(candidate.id, 60) } catch (cause: Exception) {
                if (cause is CancellationException || cause is InterruptedException) throw cause
                onFailure(candidate.id, cause)
            }
            if (recovered) recoveredCount++
        }
        return recoveredCount
    }

    /** true: recovered, false: nothing to do after revalidation, null: a row was locked by someone else. */
    private fun recoverLocked(connection: Connection, candidate: Candidate): Boolean? {
        if(!connection.lockAnalysisOwner(candidate.itemId,skipLocked=true)) return null
        val item = connection.lockAnalysisItem(candidate.itemId, skipLocked = true) ?: return null
        val job = connection.lockAnalysisJob(candidate.id, skipLocked = true) ?: return null
        if (job.itemId != candidate.itemId || job.generation != candidate.generation || job.stage != candidate.stage ||
            job.executionToken != candidate.token || job.leaseUntil != candidate.lease || job.claimedItemVersion != candidate.version) return false
        val now = connection.analysisDatabaseTime()
        val legacy = job.executionToken == null && job.claimedItemVersion == null
        if (job.leaseUntil?.isAfter(now) == true || (job.leaseUntil == null && !legacy)) return false

        val browser = job.stage == "BROWSER_RUNNING"
        if (!item.canAnalyze(job.generation) || (!legacy && job.claimedItemVersion != item.version) ||
            (browser && !job.browserAttempted)) {
            connection.transitionAnalysisJob(candidate.id, "CANCELLED")
            if (item.canAnalyze(job.generation)) {
                // The current processing item has lost its execution; preserve edited data.
                connection.failRetryableItem(candidate.itemId)
            }
            return true
        }
        val lane = if (browser) AnalysisLane.BROWSER else AnalysisLane.GENERAL
        val attempts = job.attemptsFor(lane)
        if (!job.hasRetryBudget(now)) {
            connection.failExhausted(candidate.id, candidate.itemId)
        } else {
            connection.transitionAnalysisJob(candidate.id, if (browser) "BROWSER_PENDING" else "GENERAL_PENDING")
            connection.insertAnalysisOutbox(candidate.id, lane,
                "${if (browser) "browser" else "analysis"}-${candidate.id}-${job.generation}-recovery-$attempts-${job.executionToken ?: UUID.randomUUID()}")
        }
        return true
    }

    private data class Candidate(val id: UUID, val itemId: UUID, val generation: Int, val stage: String,
        val token: UUID?, val lease: Instant?, val version: Int?)
}
