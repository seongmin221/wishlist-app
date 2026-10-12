package app.analysis

import java.util.UUID
import javax.sql.DataSource

/**
 * Pushes a skipped or failed recovery candidate behind later ones so a stuck batch head cannot starve the rest.
 * Only the job row changes (updated_at stays, it is the staleness and revalidation key); a locked job is left alone
 * instead of waiting, which keeps the owner -> item -> job lock order intact.
 */
internal fun DataSource.deferRecoveryCheck(jobId: UUID, seconds: Long) = connection.use { c ->
    c.autoCommit = false
    try {
        val locked = c.prepareStatement("select id from analysis_jobs where id=? for update skip locked").use { s ->
            s.setObject(1, jobId); s.executeQuery().use { it.next() }
        }
        if (locked) c.prepareStatement("update analysis_jobs set recovery_check_at=clock_timestamp()+make_interval(secs => ?) where id=?").use { s ->
            s.setDouble(1, seconds.toDouble()); s.setObject(2, jobId); s.executeUpdate()
        }
        c.commit()
    } catch (cause: Throwable) { c.rollback(); throw cause }
}
