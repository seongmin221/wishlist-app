package app.media

import app.persistence.inTransaction
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class MediaCleanupReport(val deleted: Int, val deleteFailed: Int)

/**
 * OPS-01 media step. Each candidate is leased as DELETING in a short transaction, its object is deleted outside any
 * transaction, then the row becomes a DELETED tombstone. A failed delete keeps the lease row for a later run.
 */
class MediaCleanup(
    private val source: DataSource,
    private val storage: MediaStorage,
    private val batch: Int = 50,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Test hook after the lease commits and before the object delete. */
    private val beforeDelete: (UUID) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(MediaCleanup::class.java)

    fun run(deadline: Long): MediaCleanupReport {
        if (nanoTime() - deadline >= 0) return MediaCleanupReport(0, 0)
        val candidates = source.inTransaction { c ->
            c.prepareStatement("select m.id from media_assets m where ${MediaReferences.cleanupCandidate("m")} order by m.updated_at, m.id limit ?").use { s ->
                s.setInt(1, batch)
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            }
        }
        var deleted = 0
        var failed = 0
        for (id in candidates) {
            if (nanoTime() - deadline >= 0) break
            val objectKey = lease(id) ?: continue
            beforeDelete(id)
            try {
                storage.delete(objectKey)
            } catch (_: MediaStorageException) {
                log.warn("Media object delete failed mediaId={}", id)
                failed++
                continue
            }
            source.inTransaction { c ->
                c.prepareStatement("update media_assets set status='DELETED', deleted_at=clock_timestamp(), updated_at=clock_timestamp() where id=? and status='DELETING'").use { s ->
                    s.setObject(1, id); s.executeUpdate()
                }
            }
            deleted++
        }
        return MediaCleanupReport(deleted, failed)
    }

    /**
     * Locks the row alone, then re-checks the candidate rule in a separate statement. They must not be one statement:
     * after waiting for a lock PostgreSQL re-evaluates only the locked row's own conditions and keeps the reference
     * subquery's first snapshot, so a link committed meanwhile would look unreferenced. Under READ COMMITTED the second
     * statement takes a new snapshot and sees it.
     */
    private fun lease(id: UUID): String? = source.inTransaction { c ->
        val locked = c.prepareStatement("select id from media_assets where id=? for update skip locked").use { s ->
            s.setObject(1, id); s.executeQuery().use { it.next() }
        }
        if (!locked) return@inTransaction null
        val objectKey = c.prepareStatement("select m.object_key from media_assets m where m.id=? and ${MediaReferences.cleanupCandidate("m")}").use { s ->
            s.setObject(1, id); s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        } ?: return@inTransaction null
        c.prepareStatement("update media_assets set status='DELETING', updated_at=clock_timestamp() where id=?").use { s ->
            s.setObject(1, id); check(s.executeUpdate() == 1)
        }
        objectKey
    }
}
