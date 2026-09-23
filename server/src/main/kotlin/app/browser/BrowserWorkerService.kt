package app.browser

import app.analysis.WorkerDisposition
import app.extraction.Metadata
import app.extraction.UnsafeUrlException
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class BrowserNavigationTimeout : RuntimeException()
class BrowserSiteBlocked : RuntimeException()
class BrowserTargetUnavailable : RuntimeException()

class BrowserWorkerService(
    private val dataSource: DataSource,
    private val render: (UUID) -> Metadata?,
) {
    fun runBrowser(jobId: UUID, generation: Int): WorkerDisposition {
        val itemId = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val claimed = connection.prepareStatement(
                    """select j.wishlist_item_id from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id
                       where j.id=? and j.generation=? and j.stage='BROWSER_PENDING' and j.browser_attempted=true
                         and i.lifecycle_status='ACTIVE' for update of j""",
                ).use {
                    it.setObject(1, jobId)
                    it.setInt(2, generation)
                    it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
                }
                if (claimed != null) connection.prepareStatement("update analysis_jobs set stage='BROWSER_RUNNING', browser_attempt_count=browser_attempt_count+1, first_browser_attempt_at=coalesce(first_browser_attempt_at, now()), updated_at=now() where id=?").use {
                    it.setObject(1, jobId)
                    it.executeUpdate()
                }
                connection.commit()
                claimed
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        } ?: return WorkerDisposition.ACKNOWLEDGE

        val metadata = try { render(jobId) } catch (_: BrowserNavigationTimeout) { null }
            catch (_: BrowserSiteBlocked) { null }
            catch (_: BrowserTargetUnavailable) { null }
            catch (_: UnsafeUrlException) { null }
        val success = metadata != null
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (metadata != null) connection.prepareStatement("update wishlist_items set product_name=?, product_description=?, product_image_url=?, canonical_url=? where id=? and lifecycle_status='ACTIVE'").use {
                    it.setString(1, metadata.title)
                    it.setString(2, metadata.description)
                    it.setString(3, metadata.imageUrl)
                    it.setString(4, metadata.canonicalUrl)
                    it.setObject(5, itemId)
                    it.executeUpdate()
                }
                connection.prepareStatement("update analysis_jobs set stage=?, updated_at=now() where id=? and stage='BROWSER_RUNNING'").use {
                    it.setString(1, if (success) "COMPLETE" else "PARTIAL")
                    it.setObject(2, jobId)
                    it.executeUpdate()
                }
                connection.prepareStatement("update wishlist_items set analysis_status=?, version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use {
                    it.setString(1, if (success) "READY" else "PARTIAL")
                    it.setObject(2, itemId)
                    it.executeUpdate()
                }
                connection.commit()
                WorkerDisposition.ACKNOWLEDGE
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }
}
