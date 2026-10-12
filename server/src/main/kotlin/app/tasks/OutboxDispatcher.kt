package app.tasks

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

data class DispatchReport(val published: Int, val failed: Int)

class OutboxDispatcher(private val dataSource: DataSource, private val gateway: TaskGateway) {
    fun dispatchPending(limit: Int): Int = dispatchPending(limit, Long.MAX_VALUE).published

    /**
     * Backlog publication for the maintenance run. A failed event is skipped for the rest of this run so one
     * broken event cannot block later ones; its released lease lets the next run try it again.
     */
    fun dispatchPending(limit: Int, deadlineNanos: Long): DispatchReport {
        var published = 0
        val failed = mutableListOf<UUID>()
        while (published + failed.size < limit && System.nanoTime() - deadlineNanos < 0) {
            val event = claim(excluded = failed) ?: break
            if (publish(event)) published++ else failed += event.id
        }
        return DispatchReport(published, failed.size)
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
            // Messages may carry request data; type and event ID are enough to trace the stranded event.
            logger.warn("Outbox publication failed eventId={} exceptionType={}", event.id, cause.javaClass.name)
            false
        }
    }

    private fun claim(eventId: UUID? = null, excluded: List<UUID> = emptyList()): ClaimedEvent? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val event = connection.prepareStatement(
                """select e.id, e.task_name, e.event_type, j.id as job_id, j.generation,
                          case when e.not_before > clock_timestamp() then e.not_before end as schedule_at
                   from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id
                   where e.published_at is null and (e.lease_until is null or e.lease_until < clock_timestamp())
                   ${if (eventId != null) "and e.id = ?" else ""} and not (e.id = any(?))
                   order by e.created_at for update of e skip locked limit 1""",
            ).use { statement ->
                var parameter = 1
                if (eventId != null) statement.setObject(parameter++, eventId)
                statement.setArray(parameter, connection.createArrayOf("uuid", excluded.toTypedArray()))
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else ClaimedEvent(
                        rows.getObject("id", UUID::class.java),
                        AnalysisTask(rows.getString("task_name"), rows.getObject("job_id", UUID::class.java), rows.getInt("generation"),
                            rows.getString("event_type"), rows.getTimestamp("schedule_at")?.toInstant()),
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

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxDispatcher::class.java)
    }
}
