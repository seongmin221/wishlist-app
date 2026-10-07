package app.analysis

import app.category.CategoryRef
import app.purpose.PurposeMembership
import app.wishlist.AnalysisFailureCode
import app.wishlist.CategoryMissingReason
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class AnalysisResultRepository(private val dataSource: DataSource) {
    fun finish(claim: AnalysisClaim, outcome: ProcessingOutcome): WorkerDisposition {
        return dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val execution = AnalysisWriteGuard.lockExecution(c, claim)
                val result = when {
                    execution == null || !execution.item.canAnalyze(claim.generation) -> WorkerDisposition.ACKNOWLEDGE
                    execution.item.version != claim.expectedItemVersion -> {
                        c.transitionAnalysisJob(claim.jobId, "CANCELLED")
                        c.failRetryableItem(claim.itemId)
                        WorkerDisposition.ACKNOWLEDGE
                    }
                    outcome == ProcessingOutcome.Stale || execution.job.leaseUntil?.isAfter(c.analysisDatabaseTime()) != true -> WorkerDisposition.ACKNOWLEDGE
                    else -> finishLocked(c, claim, outcome, execution.job)
                }
                c.commit()
                result
            } catch (cause: Throwable) { c.rollback(); throw cause }
        }
    }

    private fun finishLocked(c: Connection, claim: AnalysisClaim, outcome: ProcessingOutcome, job: LockedAnalysisJob): WorkerDisposition {
        val item = readItem(c, claim.itemId)
        val stored = CategoryCandidateGuard.read(c, claim)
        val validCandidates = CategoryCandidateGuard.valid(c, claim, stored)
        val preserveUserConnection=item.review in setOf("CONFIRMED","DEFERRED") || item.protects("CATEGORY",item.categorySource)
        if(!validCandidates && !preserveUserConnection) {
            c.replaceStaleCategoryJob(claim,job)
            return WorkerDisposition.ACKNOWLEDGE
        }
        if (outcome == ProcessingOutcome.NeedsBrowser && claim.lane == AnalysisLane.GENERAL && !job.browserAttempted) {
            c.transitionAnalysisJob(claim.jobId, "BROWSER_PENDING", fallback = true)
            c.prepareStatement("insert into outbox_events(id,analysis_job_id,event_type,task_name) values (?,?,'BROWSER_ANALYSIS',?)").use { s ->
                s.setObject(1, UUID.randomUUID()); s.setObject(2, claim.jobId)
                s.setString(3, "browser-${claim.jobId}-${claim.generation}"); check(s.executeUpdate() == 1)
            }
            return WorkerDisposition.ACKNOWLEDGE
        }
        if (outcome == ProcessingOutcome.Retryable) {
            if (job.hasRetryBudget(claim.lane, c.analysisDatabaseTime())) {
                c.transitionAnalysisJob(claim.jobId, "${claim.lane.name}_PENDING")
                c.prepareStatement("insert into outbox_events(id,analysis_job_id,event_type,task_name) values (?,?,?,?)").use { s ->
                    s.setObject(1, UUID.randomUUID()); s.setObject(2, claim.jobId)
                    s.setString(3, if (claim.lane == AnalysisLane.GENERAL) "GENERAL_ANALYSIS" else "BROWSER_ANALYSIS")
                    s.setString(4, "${claim.lane.name.lowercase()}-${claim.jobId}-${claim.generation}-retry-${claim.executionToken}")
                    check(s.executeUpdate() == 1)
                }
                return WorkerDisposition.RETRY
            }
        }

        val pending = readPending(c, claim.jobId)
        val assigned = validCandidates && outcome == ProcessingOutcome.Complete && !pending.category.isNullOrBlank()
        val applyCategory = assigned && !preserveUserConnection
        val category = if (applyCategory) pending.category else item.category
        val categorySource = if (applyCategory) "AI" else item.categorySource
        val effectiveOutcome = if (!validCandidates && preserveUserConnection && outcome == ProcessingOutcome.Partial)
            ProcessingOutcome.Complete else outcome
        val status = when (effectiveOutcome) {
            ProcessingOutcome.Complete -> if (!category.isNullOrBlank()) "READY" else "PARTIAL"
            ProcessingOutcome.Terminal -> "FAILED_TERMINAL"
            ProcessingOutcome.Retryable -> "FAILED_RETRYABLE"
            else -> "PARTIAL"
        }
        val complete = status == "READY"
        val failure = when {
            complete || (!validCandidates && preserveUserConnection) || (assigned && item.protects("CATEGORY", item.categorySource)) -> null
            outcome == ProcessingOutcome.Complete -> AnalysisFailureCode.AI_INVALID_CANDIDATE.name
            else -> pending.failure
        }
        val name = mergedMetadata(item.name, pending.name, complete, item.protects("NAME", item.nameSource))
        val image = mergedMetadata(item.image, pending.image, complete, item.protects("IMAGE", item.imageSource))
        val nameSource = mergedSource(item.name, pending.name, item.nameSource, complete, item.protects("NAME", item.nameSource))
        val imageSource = mergedSource(item.image, pending.image, item.imageSource, complete, item.protects("IMAGE", item.imageSource))
        val decision = if (assigned) PurposeCandidateGuard.decide(c, claim, stored, pending.purpose, pending.purposeJudged, item.purpose)
            else PurposeDecision.NoJudgment
        // Reviewed items, user sources and overrides keep purpose; "no judgement" keeps the existing connection.
        val applyPurpose = decision is PurposeDecision.Judged && !item.protects("PURPOSE", item.purposeSource) &&
            item.review !in setOf("CONFIRMED", "DEFERRED")
        val purpose = if (applyPurpose) (decision as PurposeDecision.Judged).purposeId?.toString() else item.purpose
        val purposeSource = if (applyPurpose) { if (purpose == null) "UNASSIGNED" else "AI" } else item.purposeSource
        val reason = when {
            category != null -> null
            item.protects("CATEGORY", item.categorySource) && item.missingReason != null -> item.missingReason
            else -> (AnalysisFailureCode.fromStored(failure)?.categoryMissingReason ?: CategoryMissingReason.EXTRACTION_UNRESOLVED).name
        }
        val unconfirmedAiCategory = categorySource == "AI" && (applyCategory || item.review == "PENDING")
        val unconfirmedAiPurpose = purposeSource == "AI" && !purpose.isNullOrBlank() && (applyPurpose || item.review == "PENDING")
        val review = when {
            item.review in setOf("CONFIRMED", "DEFERRED") -> item.review
            !category.isNullOrBlank() && !name.isNullOrBlank() && (unconfirmedAiCategory || unconfirmedAiPurpose) -> "PENDING"
            else -> "NOT_REQUIRED"
        }
        // Keep each column adjacent to its value; subsequent parameter positions are derived.
        val categoryRef = category?.let(CategoryRef::parse)
        val values = linkedMapOf(
            "analysis_status" to status, "product_name" to name,
            "product_description" to mergedMetadata(item.description, pending.description, complete, false),
            "product_image_url" to image, "canonical_url" to mergedMetadata(item.canonical, pending.canonical, complete, false),
            "name_source" to nameSource, "image_source" to imageSource,
            "category_id" to ((categoryRef as? CategoryRef.Public)?.value ?: category?.takeIf { categoryRef == null }),
            "custom_category_id" to (categoryRef as? CategoryRef.Custom)?.value,
            "category_source" to categorySource, "category_missing_reason" to reason, "purpose_id" to purpose,
            "purpose_source" to purposeSource, "review_status" to review,
            "predicted_category_id" to if (assigned) pending.category else item.predictedCategory,
            // Diagnostics keep only purpose judgements; an unjudged or rejected result leaves the last prediction.
            "predicted_purpose_id" to if (decision is PurposeDecision.Judged) decision.purposeId?.toString() else item.predictedPurpose,
            "analysis_failure_code" to failure,
        )
        c.prepareStatement("update wishlist_items set ${values.keys.joinToString { if (it == "custom_category_id" || it == "purpose_id") "$it=?::uuid" else "$it=?" }}, " +
            "classified_at=case when ? then clock_timestamp() else classified_at end,version=version+1,updated_at=clock_timestamp() where id=?").use { s ->
            var parameter = 1
            values.values.forEach { s.setString(parameter++, it) }
            s.setBoolean(parameter++, assigned); s.setObject(parameter, claim.itemId); check(s.executeUpdate() == 1)
        }
        if (applyPurpose) PurposeMembership.recordTransition(c, claim.ownerId, item.purpose?.let(UUID::fromString), purpose?.let(UUID::fromString))
        c.transitionAnalysisJob(claim.jobId, when (status) { "READY" -> "COMPLETE"; "PARTIAL" -> "PARTIAL"; else -> "FAILED" })
        return WorkerDisposition.ACKNOWLEDGE
    }

    private fun mergedMetadata(existing: String?, pending: String?, complete: Boolean, protected: Boolean): String? =
        if (protected) existing else if (complete) pending ?: existing else existing ?: pending

    private fun mergedSource(existing: String?, pending: String?, source: String?, complete: Boolean, protected: Boolean): String? =
        if (!protected && pending != null && (complete || existing == null)) "AI" else source

    private data class Item(
        val name: String?, val description: String?, val image: String?, val canonical: String?,
        val nameSource: String?, val imageSource: String?, val category: String?, val categorySource: String?,
        val missingReason: String?, val purpose: String?, val purposeSource: String, val review: String,
        val predictedCategory: String?, val predictedPurpose: String?, val overrides: Set<String>,
    ) {
        fun protects(field: String, source: String?): Boolean = source == "USER" || field in overrides
    }

    private data class Pending(val name: String?, val description: String?, val image: String?, val canonical: String?,
        val category: String?, val purpose: String?, val failure: String?, val purposeJudged: Boolean)

    private fun readItem(c: Connection, itemId: UUID): Item = c.prepareStatement("""select product_name,product_description,product_image_url,canonical_url,
        name_source,image_source,coalesce(category_id,custom_category_id::text) category_id,category_source,category_missing_reason,purpose_id::text purpose_id,purpose_source,
        review_status,predicted_category_id,predicted_purpose_id,user_override_fields from wishlist_items where id=?""").use { s ->
        s.setObject(1, itemId)
        s.executeQuery().use { r ->
            check(r.next())
            val overrides = r.getArray("user_override_fields")
            val fields = try { (overrides.array as Array<*>).map { it as String }.toSet() } finally { overrides.free() }
            Item(r.getString("product_name"), r.getString("product_description"), r.getString("product_image_url"), r.getString("canonical_url"),
                r.getString("name_source"), r.getString("image_source"), r.getString("category_id"), r.getString("category_source"),
                r.getString("category_missing_reason"), r.getString("purpose_id"), r.getString("purpose_source"), r.getString("review_status"),
                r.getString("predicted_category_id"), r.getString("predicted_purpose_id"), fields)
        }
    }

    private fun readPending(c: Connection, jobId: UUID): Pending = c.prepareStatement("""select pending_product_name,pending_product_description,
        pending_product_image_url,pending_canonical_url,pending_category_id,pending_purpose_id,pending_failure_code,pending_purpose_judged
        from analysis_jobs where id=?""").use { s ->
        s.setObject(1, jobId)
        s.executeQuery().use { r -> check(r.next()); Pending(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getBoolean(8)) }
    }
}
