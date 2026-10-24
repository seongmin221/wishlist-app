package app.testutil

import app.ai.ClassificationResult
import app.analysis.AnalysisClaim
import app.analysis.AnalysisLane
import app.analysis.AnalysisPendingResultRepository
import app.analysis.AnalysisResultRepository
import app.analysis.ProcessingOutcome
import app.analysis.WorkerDisposition
import app.candidates.DeletionImpactService
import app.candidates.DeletionKind
import app.candidates.StructureDeletionService
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import app.wishlist.ReadPosition
import java.util.UUID
import javax.sql.DataSource

fun DataSource.impact(owner: UUID, kind: DeletionKind, id: UUID, limit: Int = 100, after: ReadPosition? = null, fingerprint: String? = null) =
    DeletionImpactService(this).get(owner, kind, id, limit, after, fingerprint)

fun DataSource.deleteStructure(owner: UUID, kind: DeletionKind, id: UUID, version: Int, token: String) =
    StructureDeletionService(this).delete(owner, kind, id, version, token)

/** Reads the current full impact and deletes with it. */
fun DataSource.deleteWithImpact(owner: UUID, kind: DeletionKind, id: UUID) =
    impact(owner, kind, id).let { deleteStructure(owner, kind, id, it.version, it.impactToken) }

/**
 * A freshly created PROCESSING item with its PENDING job (version 1, generation 1), then [assignments] applied in SQL.
 * BROWSER moves the job to the browser lane the way a fallback does.
 */
fun processingItem(source: DataSource, owner: UUID, lane: AnalysisLane, assignments: String? = null): FinishJob {
    val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/${UUID.randomUUID()}").createdItemId
    val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
    assignments?.let { analysisSql(source, "update wishlist_items set $it where id='$item'") }
    if (lane == AnalysisLane.BROWSER) analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true where id='$job'")
    return FinishJob(item, job)
}

/** Stores a Worker result while the claim is still current. */
fun savePending(source: DataSource, claim: AnalysisClaim, categoryId: String, purposeId: String? = null, judged: Boolean = false) {
    val pending = AnalysisPendingResultRepository(source)
    check(pending.saveMetadata(claim, Metadata("AI name", null, null, "https://shop.example/canonical")))
    check(pending.saveAssignment(claim, ClassificationResult.Assigned(categoryId, purposeId, judged)))
}

fun finish(source: DataSource, claim: AnalysisClaim, outcome: ProcessingOutcome = ProcessingOutcome.Complete): WorkerDisposition =
    AnalysisResultRepository(source).finish(claim, outcome)

fun jobStage(source: DataSource, job: UUID) = analysisScalar(source, "select stage from analysis_jobs where id='$job'")

fun outboxCount(source: DataSource, item: UUID) = analysisScalar(source,
    "select count(*) from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id where j.wishlist_item_id='$item'")!!.toInt()

fun deletionReceipts(source: DataSource) = analysisScalar(source, "select count(*) from structure_deletion_receipts")!!.toInt()

fun expireLease(source: DataSource, job: UUID) =
    analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='$job'")

/** One SQL row of the item's classification columns as text, overrides sorted. */
fun classification(source: DataSource, item: UUID): Map<String, String?> = itemColumns(source, item, "custom_category_id", "category_id",
    "category_source", "category_missing_reason", "user_override_fields", "purpose_id", "purpose_source", "review_status", "analysis_status",
    "version")
