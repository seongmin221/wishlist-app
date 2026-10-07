package app.analysis

import java.sql.Connection
import java.util.UUID

internal fun Connection.replaceStaleCategoryJob(claim: AnalysisClaim, job: LockedAnalysisJob) {
    if (!job.hasRetryBudget(AnalysisLane.GENERAL, analysisDatabaseTime())) {
        transitionAnalysisJob(claim.jobId, "FAILED")
        failRetryableItem(claim.itemId)
        return
    }
    transitionAnalysisJob(claim.jobId, "CANCELLED")
    val next = claim.generation + 1
    prepareStatement("update wishlist_items set current_generation=?,version=version+1,updated_at=clock_timestamp() where id=?").use { statement ->
        statement.setInt(1, next)
        statement.setObject(2, claim.itemId)
        check(statement.executeUpdate() == 1)
    }
    val id = UUID.randomUUID()
    prepareStatement("""
        insert into analysis_jobs(id,wishlist_item_id,generation,stage,attempt_count,first_attempt_at,browser_attempt_count,first_browser_attempt_at)
        select ?,wishlist_item_id,?,'GENERAL_PENDING',attempt_count,first_attempt_at,browser_attempt_count,first_browser_attempt_at
        from analysis_jobs where id=?
    """).use { statement ->
        statement.setObject(1, id)
        statement.setInt(2, next)
        statement.setObject(3, claim.jobId)
        check(statement.executeUpdate() == 1)
    }
    prepareStatement("insert into outbox_events(id,analysis_job_id,event_type,task_name) values (?,?,'GENERAL_ANALYSIS',?)").use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, id)
        statement.setString(3, "category-refresh-$id-$next")
        check(statement.executeUpdate() == 1)
    }
}
