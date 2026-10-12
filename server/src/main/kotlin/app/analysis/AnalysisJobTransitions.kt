package app.analysis

import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal const val MAX_GENERATION_ATTEMPTS = 3
internal const val GENERATION_BUDGET_SECONDS = 1800L
private const val FIRST_RETRY_SECONDS = 10L
private const val MAX_RETRY_SECONDS = 600L

internal fun LockedAnalysisJob.attemptsFor(lane: AnalysisLane): Int = if (lane == AnalysisLane.GENERAL) attempts else browserAttempts

/** One budget per generation: general and browser executions share 3 attempts and 30 minutes from the earliest attempt. */
internal fun LockedAnalysisJob.hasRetryBudget(now: Instant): Boolean {
    val first = listOfNotNull(firstAttemptAt, firstBrowserAttemptAt).minOrNull()
    return attempts + browserAttempts < MAX_GENERATION_ATTEMPTS &&
        (first == null || now.isBefore(first.plusSeconds(GENERATION_BUDGET_SECONDS)))
}

/** ADR-009 backoff: 10s after the first attempt, doubling, at most 10 minutes. */
internal fun retryBackoffSeconds(attemptsSoFar: Int): Long =
    (FIRST_RETRY_SECONDS shl (attemptsSoFar.coerceIn(1, 7) - 1)).coerceAtMost(MAX_RETRY_SECONDS)

/** Caller holds item -> job locks and has validated the current active processing item. */
internal fun Connection.failRetryableItem(itemId: UUID) {
    prepareStatement("""update wishlist_items set analysis_status='FAILED_RETRYABLE',version=version+1,
        updated_at=clock_timestamp() where id=?""").use { statement ->
        statement.setObject(1, itemId); check(statement.executeUpdate() == 1)
    }
}

/**
 * Budget exhaustion on a current execution: the job fails and the item keeps whatever page metadata the
 * generation already read, merged like any non-success result. Caller holds owner -> item -> job locks.
 */
internal fun Connection.failExhausted(jobId: UUID, itemId: UUID) {
    val merged = mergeMetadata(readStoredMetadata(itemId), readPendingMetadata(jobId), complete = false)
    transitionAnalysisJob(jobId, "FAILED")
    updateAnalyzedItem(itemId, linkedMapOf<String, Any?>("analysis_status" to "FAILED_RETRYABLE") + merged.assignments(),
        merged.recordCheckedAt, classified = false)
}

/** One outbox row per analysis task; the task name is the Cloud Tasks dedupe key, a delay becomes its scheduleTime. */
internal fun Connection.insertAnalysisOutbox(jobId: UUID, lane: AnalysisLane, taskName: String, delaySeconds: Long? = null) {
    prepareStatement("""insert into outbox_events(id,analysis_job_id,event_type,task_name,not_before)
        values (?,?,?,?,case when ?::double precision is null then null else clock_timestamp()+make_interval(secs => ?::double precision) end)""").use { s ->
        s.setObject(1, UUID.randomUUID()); s.setObject(2, jobId)
        s.setString(3, if (lane == AnalysisLane.GENERAL) "GENERAL_ANALYSIS" else "BROWSER_ANALYSIS")
        s.setString(4, taskName)
        val delay = delaySeconds?.toDouble()
        s.setObject(5, delay, java.sql.Types.DOUBLE); s.setObject(6, delay, java.sql.Types.DOUBLE)
        check(s.executeUpdate() == 1)
    }
}

internal fun Connection.transitionAnalysisJob(jobId: UUID, stage: String, fallback: Boolean = false) {
    prepareStatement("""update analysis_jobs set stage=?,execution_token=null,lease_until=null,claimed_item_version=null,
        browser_attempted=browser_attempted or ?,recovery_check_at=null,updated_at=clock_timestamp() where id=?""").use { statement ->
        statement.setString(1, stage); statement.setBoolean(2, fallback); statement.setObject(3, jobId)
        check(statement.executeUpdate() == 1)
    }
}
