package app.analysis

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class WorkerDisposition { ACKNOWLEDGE, RETRY }
enum class ProcessingOutcome { Complete, NeedsBrowser, Partial, Retryable, Terminal }

class GeneralWorkerService(
    private val dataSource: DataSource,
    private val process: (UUID) -> ProcessingOutcome,
) {
    fun runGeneral(jobId: UUID, generation: Int): WorkerDisposition {
        val claim = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val value = connection.prepareStatement(
                    """select j.id, j.wishlist_item_id, j.attempt_count, j.first_attempt_at
                       from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id
                       where j.id=? and j.generation=? and j.stage='GENERAL_PENDING' and i.lifecycle_status='ACTIVE'
                       for update of j""",
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setInt(2, generation)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else Claim(rows.getObject("wishlist_item_id", UUID::class.java), rows.getInt("attempt_count"), rows.getTimestamp("first_attempt_at")?.toInstant())
                    }
                }
                if (value != null) {
                    if (value.attempts >= 3 || value.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true) {
                        connection.failRetryable(jobId, value.itemId)
                    } else {
                        connection.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING', attempt_count=attempt_count+1, first_attempt_at=coalesce(first_attempt_at, now()), updated_at=now() where id=?").use {
                            it.setObject(1, jobId)
                            it.executeUpdate()
                        }
                    }
                }
                connection.commit()
                if (value == null || value.attempts >= 3 || value.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true) null else value
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        } ?: return WorkerDisposition.ACKNOWLEDGE

        val outcome = try { process(jobId) } catch (_: Exception) { ProcessingOutcome.Retryable }
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val disposition = when (outcome) {
                    ProcessingOutcome.Complete -> {
                        connection.prepareStatement("update analysis_jobs set stage='COMPLETE', updated_at=now() where id=? and stage='GENERAL_RUNNING'").use { it.setObject(1, jobId); it.executeUpdate() }
                        connection.prepareStatement("update wishlist_items set analysis_status='READY', version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1, claim.itemId); it.executeUpdate() }
                        WorkerDisposition.ACKNOWLEDGE
                    }
                    ProcessingOutcome.NeedsBrowser -> {
                        connection.prepareStatement("update analysis_jobs set stage='BROWSER_PENDING', browser_attempted=true, updated_at=now() where id=? and stage='GENERAL_RUNNING' and browser_attempted=false").use {
                            it.setObject(1, jobId)
                            check(it.executeUpdate() == 1) { "browser fallback already attempted" }
                        }
                        connection.prepareStatement("insert into outbox_events (id, analysis_job_id, event_type, task_name) values (?, ?, 'BROWSER_ANALYSIS', ?)").use {
                            it.setObject(1, UUID.randomUUID())
                            it.setObject(2, jobId)
                            it.setString(3, "browser-$jobId-$generation")
                            it.executeUpdate()
                        }
                        WorkerDisposition.ACKNOWLEDGE
                    }
                    ProcessingOutcome.Terminal -> {
                        connection.prepareStatement("update analysis_jobs set stage='FAILED', updated_at=now() where id=?").use { it.setObject(1, jobId); it.executeUpdate() }
                        connection.prepareStatement("update wishlist_items set analysis_status='FAILED_TERMINAL', version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1, claim.itemId); it.executeUpdate() }
                        WorkerDisposition.ACKNOWLEDGE
                    }
                    ProcessingOutcome.Partial -> {
                        connection.prepareStatement("update analysis_jobs set stage='PARTIAL', updated_at=now() where id=?").use { it.setObject(1, jobId); it.executeUpdate() }
                        connection.prepareStatement("update wishlist_items set analysis_status='PARTIAL', version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1, claim.itemId); it.executeUpdate() }
                        WorkerDisposition.ACKNOWLEDGE
                    }
                    ProcessingOutcome.Retryable -> if (claim.attempts + 1 >= 3 || claim.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true) {
                        connection.failRetryable(jobId, claim.itemId)
                        WorkerDisposition.ACKNOWLEDGE
                    } else {
                        connection.prepareStatement("update analysis_jobs set stage='GENERAL_PENDING', updated_at=now() where id=?").use { it.setObject(1, jobId); it.executeUpdate() }
                        WorkerDisposition.RETRY
                    }
                }
                connection.commit()
                disposition
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun Connection.failRetryable(jobId: UUID, itemId: UUID) {
        prepareStatement("update analysis_jobs set stage='FAILED', updated_at=now() where id=?").use { it.setObject(1, jobId); it.executeUpdate() }
        prepareStatement("update wishlist_items set analysis_status='FAILED_RETRYABLE', version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1, itemId); it.executeUpdate() }
    }

    private data class Claim(val itemId: UUID, val attempts: Int, val firstAttempt: Instant?)
}
