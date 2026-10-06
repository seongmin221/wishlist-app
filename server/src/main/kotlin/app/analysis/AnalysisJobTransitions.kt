package app.analysis

import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal fun LockedAnalysisJob.attemptsFor(lane: AnalysisLane): Int = if (lane == AnalysisLane.GENERAL) attempts else browserAttempts

internal fun LockedAnalysisJob.hasRetryBudget(lane: AnalysisLane, now: Instant): Boolean {
    val first = if (lane == AnalysisLane.GENERAL) firstAttemptAt else firstBrowserAttemptAt
    return attemptsFor(lane) < 3 && first?.plusSeconds(1800)?.isAfter(now) != false
}

/** Caller holds item -> job locks and has validated the current active processing item. */
internal fun Connection.failRetryableItem(itemId: UUID) {
    prepareStatement("""update wishlist_items set analysis_status='FAILED_RETRYABLE',version=version+1,
        updated_at=clock_timestamp() where id=?""").use { statement ->
        statement.setObject(1, itemId); check(statement.executeUpdate() == 1)
    }
}

internal fun Connection.transitionAnalysisJob(jobId: UUID, stage: String, fallback: Boolean = false) {
    prepareStatement("""update analysis_jobs set stage=?,execution_token=null,lease_until=null,claimed_item_version=null,
        browser_attempted=browser_attempted or ?,updated_at=clock_timestamp() where id=?""").use { statement ->
        statement.setString(1, stage); statement.setBoolean(2, fallback); statement.setObject(3, jobId)
        check(statement.executeUpdate() == 1)
    }
}
