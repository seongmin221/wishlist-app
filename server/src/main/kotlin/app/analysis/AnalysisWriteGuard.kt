package app.analysis

import app.persistence.OwnerStructureLock
import java.sql.Connection
import java.time.Instant
import java.util.UUID

object AnalysisWriteGuard {
    /** Caller owns the transaction, including commit/rollback and releasing both locks. */
    fun lockCurrent(connection: Connection, claim: AnalysisClaim): Boolean = lockCurrentJob(connection, claim) != null

    internal fun lockCurrentJob(connection: Connection, claim: AnalysisClaim): LockedAnalysisJob? {
        val execution = lockExecution(connection, claim) ?: return null
        if (!execution.item.canAnalyze(claim.generation) || execution.item.version != claim.expectedItemVersion) return null
        // Read the clock after both lock acquisitions: waiting for a lock can expire a lease.
        return execution.job.takeIf { it.leaseUntil?.isAfter(connection.analysisDatabaseTime()) == true }
    }

    internal fun lockExecution(connection: Connection, claim: AnalysisClaim): LockedAnalysisExecution? {
        require(!connection.autoCommit) { "Analysis writes require an explicit transaction" }
        OwnerStructureLock.lock(connection,claim.ownerId)
        val item = connection.lockAnalysisItem(claim.itemId) ?: return null
        if (item.ownerId != claim.ownerId) return null
        val job = connection.lockAnalysisJob(claim.jobId) ?: return null
        if (job.itemId != claim.itemId || job.generation != claim.generation || job.stage != "${claim.lane.name}_RUNNING" ||
            job.executionToken != claim.executionToken || job.claimedItemVersion != claim.expectedItemVersion) return null
        return LockedAnalysisExecution(item, job)
    }
}

internal data class LockedAnalysisExecution(val item: LockedAnalysisItem, val job: LockedAnalysisJob)

internal data class LockedAnalysisItem(
    val ownerId: UUID,
    val version: Int,
    val currentGeneration: Int,
    val lifecycleStatus: String,
    val analysisStatus: String,
    val manualCompletionAt: Instant?,
) {
    fun canAnalyze(generation: Int): Boolean = currentGeneration == generation && lifecycleStatus == "ACTIVE" &&
        analysisStatus == "PROCESSING" && manualCompletionAt == null
}

internal data class LockedAnalysisJob(
    val itemId: UUID,
    val generation: Int,
    val stage: String,
    val attempts: Int,
    val firstAttemptAt: Instant?,
    val browserAttempts: Int,
    val firstBrowserAttemptAt: Instant?,
    val browserAttempted: Boolean,
    val executionToken: UUID?,
    val leaseUntil: Instant?,
    val claimedItemVersion: Int?,
)

internal fun Connection.lockAnalysisItem(itemId: UUID, skipLocked: Boolean = false): LockedAnalysisItem? = prepareStatement("""
    select owner_id,version,current_generation,lifecycle_status,analysis_status,manual_completion_at
    from wishlist_items where id=? for update ${if (skipLocked) "skip locked" else ""}
""".trimIndent()).use { statement ->
    statement.setObject(1, itemId)
    statement.executeQuery().use { rows ->
        if (!rows.next()) null else LockedAnalysisItem(
            rows.getObject("owner_id", UUID::class.java), rows.getInt("version"), rows.getInt("current_generation"),
            rows.getString("lifecycle_status"), rows.getString("analysis_status"), rows.getTimestamp("manual_completion_at")?.toInstant(),
        )
    }
}

internal fun Connection.lockAnalysisJob(jobId: UUID, skipLocked: Boolean = false): LockedAnalysisJob? = prepareStatement("""
    select wishlist_item_id,generation,stage,attempt_count,first_attempt_at,browser_attempt_count,
           first_browser_attempt_at,browser_attempted,execution_token,lease_until,claimed_item_version
    from analysis_jobs where id=? for update ${if (skipLocked) "skip locked" else ""}
""".trimIndent()).use { statement ->
    statement.setObject(1, jobId)
    statement.executeQuery().use { rows ->
        if (!rows.next()) null else LockedAnalysisJob(
            rows.getObject("wishlist_item_id", UUID::class.java), rows.getInt("generation"), rows.getString("stage"),
            rows.getInt("attempt_count"), rows.getTimestamp("first_attempt_at")?.toInstant(),
            rows.getInt("browser_attempt_count"), rows.getTimestamp("first_browser_attempt_at")?.toInstant(),
            rows.getBoolean("browser_attempted"), rows.getObject("execution_token", UUID::class.java),
            rows.getTimestamp("lease_until")?.toInstant(), rows.getObject("claimed_item_version") as Int?,
        )
    }
}

internal fun Connection.analysisDatabaseTime(): Instant = createStatement().use { statement ->
    statement.executeQuery("select clock_timestamp()").use { rows -> check(rows.next()); rows.getTimestamp(1).toInstant() }
}

internal fun Connection.lockAnalysisOwner(itemId:UUID,skipLocked:Boolean=false):Boolean {
    val owner=prepareStatement("select owner_id from wishlist_items where id=?").use { s ->
        s.setObject(1,itemId);s.executeQuery().use { r -> if(r.next()) r.getObject(1,UUID::class.java) else null }
    } ?: return false
    return OwnerStructureLock.lock(this,owner,skipLocked)
}
