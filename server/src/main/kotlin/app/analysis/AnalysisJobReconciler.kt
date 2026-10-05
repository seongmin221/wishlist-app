package app.analysis

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class AnalysisJobReconciler(private val dataSource: DataSource) {
    fun reconcileExpired(): Int {
        // Discovery has no row locks. Each candidate is rechecked under item -> job locks.
        val candidates = dataSource.connection.use { connection ->
            connection.prepareStatement("""
                select id,wishlist_item_id,generation,stage,execution_token,lease_until,claimed_item_version
                from analysis_jobs where stage in ('GENERAL_RUNNING','BROWSER_RUNNING')
                and (lease_until<=clock_timestamp() or (execution_token is null and lease_until is null and claimed_item_version is null))
                order by wishlist_item_id,id
            """.trimIndent()).use { statement ->
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
        return candidates.count { candidate ->
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val changed = recoverLocked(connection, candidate)
                    connection.commit()
                    changed
                } catch (cause: Throwable) {
                    connection.rollback()
                    throw cause
                }
            }
        }
    }

    private fun recoverLocked(connection: Connection, candidate: Candidate): Boolean {
        val item = connection.lockAnalysisItem(candidate.itemId, skipLocked = true) ?: return false
        val job = connection.lockAnalysisJob(candidate.id, skipLocked = true) ?: return false
        if (job.itemId != candidate.itemId || job.generation != candidate.generation || job.stage != candidate.stage ||
            job.executionToken != candidate.token || job.leaseUntil != candidate.lease || job.claimedItemVersion != candidate.version) return false
        val now = connection.analysisDatabaseTime()
        val legacy = job.executionToken == null && job.claimedItemVersion == null
        if (job.leaseUntil?.isAfter(now) == true || (job.leaseUntil == null && !legacy)) return false

        val browser = job.stage == "BROWSER_RUNNING"
        if (!item.canAnalyze(job.generation) || (!legacy && job.claimedItemVersion != item.version) ||
            (browser && !job.browserAttempted)) {
            transition(connection, candidate.id, "CANCELLED")
            return true
        }
        val attempts = if (browser) job.browserAttempts else job.attempts
        val first = if (browser) job.firstBrowserAttemptAt else job.firstAttemptAt
        if (attempts >= 3 || first?.plusSeconds(1800)?.isAfter(now) == false) {
            transition(connection, candidate.id, "FAILED")
            connection.prepareStatement("""update wishlist_items set analysis_status='FAILED_RETRYABLE',version=version+1,
                updated_at=clock_timestamp() where id=?""").use { statement ->
                statement.setObject(1, candidate.itemId); check(statement.executeUpdate() == 1)
            }
        } else {
            transition(connection, candidate.id, if (browser) "BROWSER_PENDING" else "GENERAL_PENDING")
            connection.prepareStatement("insert into outbox_events(id,analysis_job_id,event_type,task_name) values (?,?,?,?)").use { statement ->
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, candidate.id)
                statement.setString(3, if (browser) "BROWSER_ANALYSIS" else "GENERAL_ANALYSIS")
                statement.setString(4, "${if (browser) "browser" else "analysis"}-${candidate.id}-${job.generation}-recovery-$attempts-${job.executionToken ?: UUID.randomUUID()}")
                check(statement.executeUpdate() == 1)
            }
        }
        return true
    }

    private fun transition(connection: Connection, jobId: UUID, stage: String) {
        connection.prepareStatement("""update analysis_jobs set stage=?,execution_token=null,lease_until=null,claimed_item_version=null,
            updated_at=clock_timestamp() where id=?""").use { statement ->
            statement.setString(1, stage); statement.setObject(2, jobId); check(statement.executeUpdate() == 1)
        }
    }

    private data class Candidate(val id: UUID, val itemId: UUID, val generation: Int, val stage: String,
        val token: UUID?, val lease: Instant?, val version: Int?)
}
