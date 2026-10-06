package app.testutil

import app.analysis.*
import app.ai.ClassificationResult
import app.browser.BrowserWorkerService
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class FinishJob(val itemId: UUID, val jobId: UUID)

fun newFinishJob(source: DataSource, lane: AnalysisLane): FinishJob {
    val item = CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item").itemId
    val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
    if (lane == AnalysisLane.BROWSER) analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true where id='$job'")
    return FinishJob(item, job)
}

fun seedFinishResult(source: DataSource, claim: AnalysisClaim) {
    val pending = AnalysisPendingResultRepository(source)
    check(pending.saveMetadata(claim, Metadata("AI name", "AI description", "https://example.com/ai-image", "https://example.com/canonical")))
    check(pending.saveAssignment(claim, ClassificationResult.Assigned("C026", "AI_PURPOSE")))
    check(pending.saveFailure(claim, app.wishlist.AnalysisFailureCode.AI_ABSTAINED))
}

fun finishSnapshot(source: DataSource, job: FinishJob): List<String?> = listOf(
    analysisScalar(source, "select to_jsonb(i)::text from wishlist_items i where id='${job.itemId}'"),
    analysisScalar(source, "select jsonb_agg(to_jsonb(j) order by id)::text from analysis_jobs j where wishlist_item_id='${job.itemId}'"),
    analysisScalar(source, "select jsonb_agg(to_jsonb(e) order by e.id)::text from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id where j.wishlist_item_id='${job.itemId}'"),
)

fun assertStaleFinishMatrix(source: DataSource, lane: AnalysisLane) {
    val outcomes = listOf(ProcessingOutcome.Complete, ProcessingOutcome.Partial, ProcessingOutcome.Terminal, ProcessingOutcome.Retryable) +
        if (lane == AnalysisLane.GENERAL) listOf(ProcessingOutcome.NeedsBrowser) else emptyList()
    for (outcome in outcomes) for (change in listOf("generation", "archived", "deleted", "manual", "version", "lease", "token")) {
        val job = newFinishJob(source, lane)
        var claim: AnalysisClaim? = null
        var expected: List<String?> = emptyList()
        val result = pausedAnalysisCall({ pause ->
            val process: (AnalysisClaim) -> ProcessingOutcome = { active -> claim = active; seedFinishResult(source, active); pause(); outcome }
            if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, process).runGeneral(job.jobId, 1)
            else BrowserWorkerService(source, { Metadata("render", null, null, "https://example.com/item") }, { active, _ -> process(active) }).runBrowser(job.jobId, 1)
        }, {
            checkNotNull(claim)
            val assignment = when (change) {
                "archived" -> "lifecycle_status='ARCHIVED'"
                "deleted" -> "lifecycle_status='DELETED'"
                "manual" -> "manual_completion_at=clock_timestamp()"
                "version" -> "version=version+1"
                else -> "current_generation=2"
            }
            if (change == "lease" || change == "token") {
                analysisSql(source, "update analysis_jobs set ${if (change == "lease") "lease_until=clock_timestamp()-interval '1 second'" else "execution_token='${UUID.randomUUID()}'"} where id='${job.jobId}'")
            } else analysisSql(source, "update wishlist_items set $assignment where id='${job.itemId}'")
            if (change == "generation") {
                analysisSql(source, "insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('${UUID.randomUUID()}','${job.itemId}',2,'GENERAL_PENDING')")
            }
            expected = finishSnapshot(source, job)
        })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, result, "$lane $outcome $change")
        val after = finishSnapshot(source, job)
        if (change == "version") {
            val preserved = setOf("analysis_status", "version", "updated_at")
            assertEquals(Json.parseToJsonElement(expected[0]!!).jsonObject.filterKeys { it !in preserved },
                Json.parseToJsonElement(after[0]!!).jsonObject.filterKeys { it !in preserved })
            assertEquals("FAILED_RETRYABLE", analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
            assertEquals("3", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
            assertEquals("CANCELLED", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            for (column in listOf("execution_token", "lease_until", "claimed_item_version"))
                assertNull(analysisScalar(source, "select $column from analysis_jobs where id='${job.jobId}'"))
            assertEquals(expected[2], after[2])
        } else assertEquals(expected, after, "$lane $outcome $change")
    }
}

fun assertNormalFinishMatrix(source: DataSource, lane: AnalysisLane) {
    val cases = listOf(
        Triple(ProcessingOutcome.Complete, "READY", "COMPLETE"), Triple(ProcessingOutcome.Partial, "PARTIAL", "PARTIAL"),
        Triple(ProcessingOutcome.Terminal, "FAILED_TERMINAL", "FAILED"), Triple(ProcessingOutcome.Retryable, "PROCESSING", "${lane.name}_PENDING"),
    ) + if (lane == AnalysisLane.GENERAL) listOf(Triple(ProcessingOutcome.NeedsBrowser, "PROCESSING", "BROWSER_PENDING")) else emptyList()
    for ((outcome, status, stage) in cases) {
        val job = newFinishJob(source, lane)
        val process: (AnalysisClaim) -> ProcessingOutcome = { claim -> seedFinishResult(source, claim); outcome }
        val result = if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, process).runGeneral(job.jobId, 1)
            else BrowserWorkerService(source, { Metadata("render", null, null, "https://example.com/item") }, { claim, _ -> process(claim) }).runBrowser(job.jobId, 1)
        assertEquals(if (outcome == ProcessingOutcome.Retryable) WorkerDisposition.RETRY else WorkerDisposition.ACKNOWLEDGE, result)
        assertEquals(status, analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
        assertEquals(stage, analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
        val final = outcome != ProcessingOutcome.Retryable && outcome != ProcessingOutcome.NeedsBrowser
        assertEquals(if (final) "2" else "1", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        assertNull(analysisScalar(source, "select execution_token from analysis_jobs where id='${job.jobId}'"))
        assertNull(analysisScalar(source, "select lease_until from analysis_jobs where id='${job.jobId}'"))
        assertNull(analysisScalar(source, "select claimed_item_version from analysis_jobs where id='${job.jobId}'"))
        if (outcome == ProcessingOutcome.Complete) {
            assertEquals("C026", analysisScalar(source, "select category_id from wishlist_items where id='${job.itemId}'"))
            assertEquals("AI", analysisScalar(source, "select category_source from wishlist_items where id='${job.itemId}'"))
            assertEquals("AI_PURPOSE", analysisScalar(source, "select purpose_id from wishlist_items where id='${job.itemId}'"))
            assertEquals("AI", analysisScalar(source, "select purpose_source from wishlist_items where id='${job.itemId}'"))
            assertEquals("PENDING", analysisScalar(source, "select review_status from wishlist_items where id='${job.itemId}'"))
            assertNull(analysisScalar(source, "select category_missing_reason from wishlist_items where id='${job.itemId}'"))
            assertNull(analysisScalar(source, "select analysis_failure_code from wishlist_items where id='${job.itemId}'"))
        }
        if (outcome == ProcessingOutcome.NeedsBrowser) {
            assertEquals("1", analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${job.jobId}' and event_type='BROWSER_ANALYSIS'"))
            assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source) { error("must not rerun") }.runGeneral(job.jobId, 1))
            assertEquals("1", analysisScalar(source, "select count(*) from outbox_events where analysis_job_id='${job.jobId}' and event_type='BROWSER_ANALYSIS'"))
        }
    }
}

fun assertProtectedFinishMatrix(source: DataSource, lane: AnalysisLane) {
    for (review in listOf("CONFIRMED", "DEFERRED")) for (outcome in listOf(ProcessingOutcome.Complete, ProcessingOutcome.Partial, ProcessingOutcome.Terminal)) {
        for (byOverride in listOf(false, true)) {
            val job = newFinishJob(source, lane)
            val provenance = if (byOverride) "AI" else "USER"
            analysisSql(source, """update wishlist_items set product_name='kept name',product_image_url=null,name_source='$provenance',image_source='$provenance',
                category_id='KEPT_CAT',category_source='$provenance',category_missing_reason=null,purpose_id=null,purpose_source='${if (byOverride) "UNASSIGNED" else "USER"}',
                review_status='$review',user_override_fields=${if (byOverride) "array['NAME','IMAGE','CATEGORY','PURPOSE']" else "'{}'::text[]"}
                where id='${job.itemId}'""".trimIndent())
            val process: (AnalysisClaim) -> ProcessingOutcome = { claim -> seedFinishResult(source, claim); outcome }
            val result = if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, process).runGeneral(job.jobId, 1)
                else BrowserWorkerService(source, { Metadata("render", null, null, "https://example.com/item") }, { claim, _ -> process(claim) }).runBrowser(job.jobId, 1)
            assertEquals(WorkerDisposition.ACKNOWLEDGE, result)
            assertEquals("kept name", analysisScalar(source, "select product_name from wishlist_items where id='${job.itemId}'"))
            assertNull(analysisScalar(source, "select product_image_url from wishlist_items where id='${job.itemId}'"))
            assertEquals(provenance, analysisScalar(source, "select name_source from wishlist_items where id='${job.itemId}'"))
            assertEquals("KEPT_CAT", analysisScalar(source, "select category_id from wishlist_items where id='${job.itemId}'"))
            assertEquals(provenance, analysisScalar(source, "select category_source from wishlist_items where id='${job.itemId}'"))
            assertNull(analysisScalar(source, "select purpose_id from wishlist_items where id='${job.itemId}'"))
            assertEquals(if (byOverride) "UNASSIGNED" else "USER", analysisScalar(source, "select purpose_source from wishlist_items where id='${job.itemId}'"))
            assertEquals(review, analysisScalar(source, "select review_status from wishlist_items where id='${job.itemId}'"))
            assertNull(analysisScalar(source, "select category_missing_reason from wishlist_items where id='${job.itemId}'"))
            assertEquals("2", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
            if (outcome == ProcessingOutcome.Complete) assertEquals("C026", analysisScalar(source, "select predicted_category_id from wishlist_items where id='${job.itemId}'"))
        }
    }
}

fun assertIncompleteFinishMetadata(source: DataSource) {
    for (lane in AnalysisLane.entries) for (outcome in listOf(ProcessingOutcome.Partial, ProcessingOutcome.Terminal, ProcessingOutcome.Retryable)) {
        val job = newFinishJob(source, lane)
        analysisSql(source, """update wishlist_items set product_name='old name',product_description='old description',
            product_image_url='https://example.com/old-image',canonical_url='https://example.com/old-url',name_source='AI',image_source='AI'
            where id='${job.itemId}'""")
        if (outcome == ProcessingOutcome.Retryable) analysisSql(source, "update analysis_jobs set ${if (lane == AnalysisLane.GENERAL) "attempt_count" else "browser_attempt_count"}=2 where id='${job.jobId}'")
        val process: (AnalysisClaim) -> ProcessingOutcome = { claim -> seedFinishResult(source, claim); outcome }
        val result = if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, process).runGeneral(job.jobId, 1)
            else BrowserWorkerService(source, { Metadata("render", null, null, "https://example.com/item") }, { claim, _ -> process(claim) }).runBrowser(job.jobId, 1)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, result)
        for ((column, value) in listOf("product_name" to "old name", "product_description" to "old description", "product_image_url" to "https://example.com/old-image", "canonical_url" to "https://example.com/old-url")) {
            assertEquals(value, analysisScalar(source, "select $column from wishlist_items where id='${job.itemId}'"))
        }
        assertEquals("2", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        assertNull(analysisScalar(source, "select execution_token from analysis_jobs where id='${job.jobId}'"))
        if (outcome == ProcessingOutcome.Retryable) assertEquals("FAILED_RETRYABLE", analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
    }
}

fun assertPurposeOnlyReview(source: DataSource) {
    for (lane in AnalysisLane.entries) for (review in listOf("NOT_REQUIRED", "PENDING", "CONFIRMED", "DEFERRED")) {
        val job = newFinishJob(source, lane)
        analysisSql(source, """update wishlist_items set category_id='USER_CAT',category_source='USER',category_missing_reason=null,
            review_status='$review' where id='${job.itemId}'""")
        val process: (AnalysisClaim) -> ProcessingOutcome = { claim -> seedFinishResult(source, claim); ProcessingOutcome.Complete }
        val result = if (lane == AnalysisLane.GENERAL) GeneralWorkerService(source, process).runGeneral(job.jobId, 1)
            else BrowserWorkerService(source, { Metadata("render", null, null, "https://example.com/item") }, { claim, _ -> process(claim) }).runBrowser(job.jobId, 1)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, result)
        assertEquals("USER_CAT", analysisScalar(source, "select category_id from wishlist_items where id='${job.itemId}'"))
        assertEquals("AI_PURPOSE", analysisScalar(source, "select purpose_id from wishlist_items where id='${job.itemId}'"))
        assertEquals("AI", analysisScalar(source, "select purpose_source from wishlist_items where id='${job.itemId}'"))
        assertEquals(if (review in setOf("CONFIRMED", "DEFERRED")) review else "PENDING",
            analysisScalar(source, "select review_status from wishlist_items where id='${job.itemId}'"))
    }
}
