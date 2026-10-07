package app.analysis

import java.sql.Connection
import java.util.UUID

internal fun Connection.replaceStaleCategoryJob(claim:AnalysisClaim,job:LockedAnalysisJob) {
    transitionAnalysisJob(claim.jobId,"CANCELLED")
    if(!job.hasRetryBudget(AnalysisLane.GENERAL,analysisDatabaseTime())) {
        failRetryableItem(claim.itemId)
        return
    }
    val next=claim.generation+1
    prepareStatement("update wishlist_items set current_generation=?,version=version+1,updated_at=clock_timestamp() where id=?").use { s ->
        s.setInt(1,next);s.setObject(2,claim.itemId);check(s.executeUpdate()==1)
    }
    val id=UUID.randomUUID()
    prepareStatement("""insert into analysis_jobs(id,wishlist_item_id,generation,stage,attempt_count,first_attempt_at,browser_attempt_count,first_browser_attempt_at)
        select ?,wishlist_item_id,?,'GENERAL_PENDING',attempt_count,first_attempt_at,browser_attempt_count,first_browser_attempt_at from analysis_jobs where id=?""").use { s ->
        s.setObject(1,id);s.setInt(2,next);s.setObject(3,claim.jobId);check(s.executeUpdate()==1)
    }
    prepareStatement("insert into outbox_events(id,analysis_job_id,event_type,task_name) values (?,?,'GENERAL_ANALYSIS',?)").use { s ->
        s.setObject(1,UUID.randomUUID());s.setObject(2,id);s.setString(3,"category-refresh-$id-$next");check(s.executeUpdate()==1)
    }
}
