package app.browser

import app.analysis.WorkerDisposition
import app.analysis.ProcessingOutcome
import app.extraction.Metadata
import app.extraction.UnsafeUrlException
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class BrowserNavigationTimeout : RuntimeException()
class BrowserSiteBlocked : RuntimeException()
class BrowserTargetUnavailable : RuntimeException()

class BrowserWorkerService(
    private val dataSource: DataSource,
    private val render: (UUID) -> Metadata?,
    private val classify: (UUID, Metadata) -> ProcessingOutcome,
) {
    fun runBrowser(jobId: UUID, generation: Int): WorkerDisposition {
        val claim = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val claimed = connection.prepareStatement(
                    """select j.wishlist_item_id,j.browser_attempt_count,j.first_browser_attempt_at from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id
                       where j.id=? and j.generation=? and j.stage='BROWSER_PENDING' and j.browser_attempted=true
                         and i.lifecycle_status='ACTIVE' for update of j""",
                ).use {
                    it.setObject(1, jobId)
                    it.setInt(2, generation)
                    it.executeQuery().use { rows -> if (rows.next()) Claim(rows.getObject(1, UUID::class.java), rows.getInt(2), rows.getTimestamp(3)?.toInstant()) else null }
                }
                if (claimed != null && claimed.attempts < 3 && claimed.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) != true) connection.prepareStatement("update analysis_jobs set stage='BROWSER_RUNNING', browser_attempt_count=browser_attempt_count+1, first_browser_attempt_at=coalesce(first_browser_attempt_at, now()), updated_at=now() where id=?").use {
                    it.setObject(1, jobId)
                    it.executeUpdate()
                }
                if (claimed != null && (claimed.attempts >= 3 || claimed.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true)) connection.failRetryable(jobId, claimed.itemId)
                connection.commit()
                if (claimed == null || claimed.attempts >= 3 || claimed.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true) null else claimed
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        } ?: return WorkerDisposition.ACKNOWLEDGE

        val metadata = try { render(jobId) } catch (_: BrowserNavigationTimeout) { null }
            catch (_: BrowserSiteBlocked) { null }
            catch (_: BrowserTargetUnavailable) { null }
            catch (_: UnsafeUrlException) { null }
        val outcome = if (metadata == null) ProcessingOutcome.Partial else classify(jobId, metadata)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val expired = claim.attempts + 1 >= 3 || claim.firstAttempt?.plusSeconds(1800)?.isBefore(Instant.now()) == true
                val stage = when (outcome) {
                    ProcessingOutcome.Complete -> "COMPLETE"
                    ProcessingOutcome.Retryable -> if (expired) "FAILED" else "BROWSER_PENDING"
                    ProcessingOutcome.Terminal -> "FAILED"
                    else -> "PARTIAL"
                }
                val updated = connection.prepareStatement("update analysis_jobs set stage=?, updated_at=now() where id=? and generation=? and stage='BROWSER_RUNNING'").use {
                    it.setString(1, stage)
                    it.setObject(2, jobId)
                    it.setInt(3, generation)
                    it.executeUpdate()
                }
                if (updated == 1 && metadata != null) connection.prepareStatement("update wishlist_items set product_name=?, product_description=?, product_image_url=?, canonical_url=? where id=? and lifecycle_status='ACTIVE'").use {
                    it.setString(1, metadata.title)
                    it.setString(2, metadata.description)
                    it.setString(3, metadata.imageUrl)
                    it.setString(4, metadata.canonicalUrl)
                    it.setObject(5, claim.itemId)
                    it.executeUpdate()
                }
                if (updated == 1 && stage != "BROWSER_PENDING") connection.prepareStatement("update wishlist_items set analysis_status=?, version=version+1, updated_at=now() where id=? and lifecycle_status='ACTIVE'").use {
                    it.setString(1, when (outcome) {
                        ProcessingOutcome.Complete -> "READY"
                        ProcessingOutcome.Retryable -> "FAILED_RETRYABLE"
                        ProcessingOutcome.Terminal -> "FAILED_TERMINAL"
                        else -> "PARTIAL"
                    })
                    it.setObject(2, claim.itemId)
                    it.executeUpdate()
                }
                connection.commit()
                if (updated == 1 && stage == "BROWSER_PENDING") WorkerDisposition.RETRY else WorkerDisposition.ACKNOWLEDGE
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun Connection.failRetryable(jobId: UUID, itemId: UUID) {
        prepareStatement("update analysis_jobs set stage='FAILED',updated_at=now() where id=?").use { it.setObject(1,jobId); it.executeUpdate() }
        prepareStatement("update wishlist_items set analysis_status='FAILED_RETRYABLE',version=version+1,updated_at=now() where id=? and lifecycle_status='ACTIVE'").use { it.setObject(1,itemId); it.executeUpdate() }
    }

    private data class Claim(val itemId: UUID, val attempts: Int, val firstAttempt: Instant?)
}
