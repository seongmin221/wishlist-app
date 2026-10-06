package app.tasks

import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.sql.DataSource

class OutboxDispatcher(private val dataSource: DataSource, private val gateway: TaskGateway) {
    fun dispatchPending(limit: Int): Int {
        var published = 0
        repeat(limit.coerceAtLeast(0)) {
            val event = claim() ?: return published
            if (!publish(event)) return published
            published++
        }
        return published
    }

    fun dispatchEvent(eventId: UUID): Boolean = claim(eventId)?.let(::publish) ?: false

    private fun publish(event: ClaimedEvent): Boolean {
        return try {
            gateway.create(event.task)
            dataSource.connection.use { connection ->
                connection.prepareStatement("update outbox_events set published_at=now(), lease_until=null where id=? and published_at is null").use {
                    it.setObject(1, event.id)
                    it.executeUpdate()
                }
            }
            true
        } catch (cause: Exception) {
            try {
                dataSource.connection.use { connection ->
                    connection.prepareStatement("update outbox_events set lease_until=null where id=? and published_at is null").use {
                        it.setObject(1, event.id)
                        it.executeUpdate()
                    }
                }
            } catch (cleanup: Exception) {
                if (cause is CancellationException) {
                    if (cleanup !== cause) cause.addSuppressed(cleanup)
                    throw cause
                }
                if (cleanup !== cause) cleanup.addSuppressed(cause)
                throw cleanup
            }
            if (cause is CancellationException) throw cause
            false
        }
    }

    private fun claim(eventId: UUID? = null): ClaimedEvent? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val event = connection.prepareStatement(
                """select e.id, e.task_name, e.event_type, j.id as job_id, j.generation
                   from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id
                   where e.published_at is null and (e.lease_until is null or e.lease_until < clock_timestamp())
                   ${if (eventId != null) "and e.id = ?" else ""}
                   order by e.created_at for update of e skip locked limit 1""",
            ).use { statement ->
                if (eventId != null) statement.setObject(1, eventId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else ClaimedEvent(
                        rows.getObject("id", UUID::class.java),
                        AnalysisTask(rows.getString("task_name"), rows.getObject("job_id", UUID::class.java), rows.getInt("generation"), rows.getString("event_type")),
                    )
                }
            }
            if (event != null) {
                connection.prepareStatement("update outbox_events set lease_until=clock_timestamp()+interval '120 seconds' where id=?").use {
                    it.setObject(1, event.id)
                    it.executeUpdate()
                }
            }
            connection.commit()
            event
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }

    private data class ClaimedEvent(val id: UUID, val task: AnalysisTask)
}
