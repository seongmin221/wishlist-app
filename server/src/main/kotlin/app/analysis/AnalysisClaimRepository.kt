package app.analysis

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class AnalysisClaimRepository(private val dataSource: DataSource) {
    fun claim(jobId: UUID, generation: Int, lane: AnalysisLane): ClaimResult = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = claimLocked(connection, jobId, generation, lane)
            connection.commit()
            result
        } catch (cause: Throwable) {
            connection.rollback()
            throw cause
        }
    }

    private fun claimLocked(connection: Connection, jobId: UUID, generation: Int, lane: AnalysisLane): ClaimResult {
        // This discovery read takes no row lock. Revalidate the relation after item -> job locks.
        val itemId = connection.prepareStatement("select wishlist_item_id from analysis_jobs where id=?").use { statement ->
            statement.setObject(1, jobId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: return ClaimResult.Ignored
        val item = connection.lockAnalysisItem(itemId) ?: return ClaimResult.Ignored
        val job = connection.lockAnalysisJob(jobId) ?: return ClaimResult.Ignored
        if (job.itemId == itemId && job.generation == generation && job.stage == "${lane.name}_PENDING" &&
            item.lifecycleStatus == "DELETED") {
            connection.transitionAnalysisJob(jobId, "CANCELLED")
            return ClaimResult.Ignored
        }
        if (!item.canAnalyze(generation) || job.itemId != itemId || job.generation != generation ||
            job.stage != "${lane.name}_PENDING" || (lane == AnalysisLane.BROWSER && !job.browserAttempted)) return ClaimResult.Ignored

        val now = connection.analysisDatabaseTime()
        // Connection-pool and item/job lock waits also consume the processing budget.
        if (WorkerExecution.expired()) throw ProcessingDeadlineExceeded()
        if (!job.hasRetryBudget(lane, now)) {
            connection.transitionAnalysisJob(jobId, "FAILED")
            connection.failRetryableItem(itemId)
            return ClaimResult.Exhausted
        }

        val token = UUID.randomUUID()
        val countColumn = if (lane == AnalysisLane.GENERAL) "attempt_count" else "browser_attempt_count"
        val firstColumn = if (lane == AnalysisLane.GENERAL) "first_attempt_at" else "first_browser_attempt_at"
        val clearMetadata = if (lane == AnalysisLane.GENERAL)
            "pending_product_name=null,pending_product_description=null,pending_product_image_url=null,pending_canonical_url=null," else ""
        val lease = connection.prepareStatement("""
            update analysis_jobs set stage=?,execution_token=?,lease_until=clock_timestamp()+interval '${AnalysisTiming.LEASE_SECONDS} seconds',
                claimed_item_version=?, $countColumn=$countColumn+1,$firstColumn=coalesce($firstColumn,clock_timestamp()),
                ${clearMetadata}pending_category_id=null,pending_purpose_id=null,pending_failure_code=null,updated_at=clock_timestamp()
            where id=? returning lease_until
        """.trimIndent()).use { statement ->
            statement.setString(1, "${lane.name}_RUNNING")
            statement.setObject(2, token)
            statement.setInt(3, item.version)
            statement.setObject(4, jobId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getTimestamp(1).toInstant() }
        }
        return ClaimResult.Claimed(AnalysisClaim(jobId, itemId, item.ownerId, generation, token, lane, item.version, lease))
    }
}
