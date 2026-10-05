package app.browser

import app.analysis.*
import app.analysis.ProcessingOutcome
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
    private val render: (AnalysisClaim) -> Metadata?,
    private val classify: (AnalysisClaim, Metadata) -> ProcessingOutcome,
) {
    fun runBrowser(jobId: UUID, generation: Int): WorkerDisposition {
        val claim = when (val result = AnalysisClaimRepository(dataSource).claim(jobId, generation, AnalysisLane.BROWSER)) {
            is ClaimResult.Claimed -> result.claim
            ClaimResult.Ignored, ClaimResult.Exhausted -> return WorkerDisposition.ACKNOWLEDGE
        }

        val metadata = try { render(claim) } catch (_: BrowserNavigationTimeout) { null }
            catch (_: BrowserSiteBlocked) { null }
            catch (_: BrowserTargetUnavailable) { null }
            catch (_: UnsafeUrlException) { null }
        val pending = AnalysisPendingResultRepository(dataSource)
        if (metadata != null && !pending.saveMetadata(claim, metadata)) return WorkerDisposition.ACKNOWLEDGE
        val outcome = if (metadata == null) ProcessingOutcome.Partial else classify(claim, metadata)
        if (outcome == ProcessingOutcome.Stale) return WorkerDisposition.ACKNOWLEDGE
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!AnalysisWriteGuard.lockCurrent(connection, claim)) {
                    connection.commit()
                    return@use WorkerDisposition.ACKNOWLEDGE
                }
                val job = checkNotNull(connection.lockAnalysisJob(jobId))
                val expired = job.browserAttempts >= 3 || job.firstBrowserAttemptAt?.plusSeconds(1800)?.isAfter(connection.analysisDatabaseTime()) == false
                val stage = when (outcome) {
                    ProcessingOutcome.Complete -> "COMPLETE"
                    ProcessingOutcome.Retryable -> if (expired) "FAILED" else "BROWSER_PENDING"
                    ProcessingOutcome.Terminal -> "FAILED"
                    else -> "PARTIAL"
                }
                val updated = connection.prepareStatement("update analysis_jobs set stage=?, updated_at=now() where id=? and generation=? and stage='BROWSER_RUNNING' and (? <> 'COMPLETE' or pending_category_id is not null)").use {
                    it.setString(1, stage)
                    it.setObject(2, jobId)
                    it.setInt(3, generation)
                    it.setString(4, stage)
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
                if (updated == 1 && stage != "BROWSER_PENDING") connection.prepareStatement(
                    """update wishlist_items i set product_name=coalesce(?,i.product_name),product_description=coalesce(?,i.product_description),
                       product_image_url=coalesce(?,i.product_image_url),canonical_url=coalesce(?,i.canonical_url),
                       predicted_category_id=case when ?='COMPLETE' then j.pending_category_id else i.predicted_category_id end,
                       predicted_purpose_id=case when ?='COMPLETE' then j.pending_purpose_id else i.predicted_purpose_id end,
                       classified_at=case when ?='COMPLETE' then now() else i.classified_at end,
                       analysis_failure_code=j.pending_failure_code
                       from analysis_jobs j where j.wishlist_item_id=i.id and j.id=? and i.lifecycle_status='ACTIVE'""",
                ).use {
                    it.setString(1, metadata?.title); it.setString(2, metadata?.description); it.setString(3, metadata?.imageUrl)
                    it.setString(4, metadata?.canonicalUrl); it.setString(5, stage); it.setString(6, stage); it.setString(7, stage)
                    it.setObject(8,jobId); it.executeUpdate()
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

}
