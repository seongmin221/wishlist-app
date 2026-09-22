package app.analysis

import java.util.UUID
import javax.sql.DataSource

class AnalysisJobReconciler(private val dataSource: DataSource) {
    fun reconcileExpired(): Int = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val jobs = connection.prepareStatement(
                """select j.id, j.generation, j.attempt_count, j.wishlist_item_id
                   from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id
                   where j.stage='GENERAL_RUNNING' and j.updated_at < now()-interval '120 seconds'
                     and i.lifecycle_status='ACTIVE'
                   for update of j skip locked""",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Job(rows.getObject("id", UUID::class.java), rows.getObject("wishlist_item_id", UUID::class.java), rows.getInt("generation"), rows.getInt("attempt_count")))
                    }
                }
            }
            for (job in jobs) {
                if (job.attemptCount >= 3) {
                    connection.prepareStatement("update analysis_jobs set stage='FAILED', updated_at=now() where id=?").use { it.setObject(1, job.id); it.executeUpdate() }
                    connection.prepareStatement("update wishlist_items set analysis_status='FAILED_RETRYABLE', version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1, job.itemId); it.executeUpdate() }
                } else {
                    connection.prepareStatement("update analysis_jobs set stage='GENERAL_PENDING', updated_at=now() where id=?").use { it.setObject(1, job.id); it.executeUpdate() }
                    connection.prepareStatement("insert into outbox_events (id, analysis_job_id, event_type, task_name) values (?, ?, 'GENERAL_ANALYSIS', ?)").use {
                        it.setObject(1, UUID.randomUUID())
                        it.setObject(2, job.id)
                        it.setString(3, "analysis-${job.id}-${job.generation}-recovery-${job.attemptCount}")
                        it.executeUpdate()
                    }
                }
            }
            connection.commit()
            jobs.size
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }

    private data class Job(val id: UUID, val itemId: UUID, val generation: Int, val attemptCount: Int)
}
