package app.ai

import app.analysis.ProcessingOutcome
import app.budget.LlmBudgetService
import app.budget.ReserveResult
import app.extraction.Metadata
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AiClassificationService(
    private val dataSource: DataSource,
    private val budget: LlmBudgetService,
    private val candidatesForJob: (UUID) -> CandidateSnapshot,
    private val gateway: (String, CandidateSnapshot) -> GatewayResponse,
) {
    fun classify(jobId: UUID, metadata: Metadata): ProcessingOutcome {
        val claim = dataSource.connection.use { c -> c.prepareStatement(
            """select j.generation from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id
               where j.id=? and j.stage in ('GENERAL_RUNNING','BROWSER_RUNNING') and i.lifecycle_status='ACTIVE'""",
        ).use { s -> s.setObject(1,jobId); s.executeQuery().use { r -> if (r.next()) r.getInt(1) else null } } }
            ?: return ProcessingOutcome.Terminal
        val candidates = candidateSnapshot(jobId)
        val reservation = when (val result = budget.reserveBeforeCall(jobId, claim, UUID.randomUUID())) {
            is ReserveResult.Reserved -> result.reservation
            ReserveResult.Exceeded -> {
                setFailure(jobId, "AI_BUDGET_EXCEEDED")
                return ProcessingOutcome.Partial
            }
        }
        budget.markInFlight(reservation.id)
        val result = gateway(listOfNotNull(metadata.title, metadata.description).joinToString(" "), candidates)
        val usageWithinLimit = result.inputTokens != null && result.outputTokens != null && result.inputTokens in 0..2000 && result.outputTokens in 0..80
        if (usageWithinLimit) {
            budget.settle(reservation.id, result.inputTokens, result.outputTokens)
        } else if (result.classification !is ClassificationResult.Retryable) {
            budget.settleMaximum(reservation.id)
        }
        if (result.classification is ClassificationResult.Assigned && !usageWithinLimit) {
            setFailure(jobId, "AI_USAGE_OUT_OF_RANGE")
            return ProcessingOutcome.Partial
        }
        return when (val classification = result.classification) {
            is ClassificationResult.Assigned -> {
                if (classification.categoryId !in candidates.categoryIds ||
                    (classification.purposeId != null && classification.purposeId !in candidates.purposeIds)) {
                    setFailure(jobId, "AI_INVALID_CANDIDATE")
                    ProcessingOutcome.Partial
                } else {
                    saveAssignment(jobId, classification)
                    ProcessingOutcome.Complete
                }
            }
            ClassificationResult.Abstained -> {
                setFailure(jobId, "AI_ABSTAINED")
                ProcessingOutcome.Partial
            }
            is ClassificationResult.Unusable -> {
                setFailure(jobId, "AI_UNUSABLE_RESPONSE")
                ProcessingOutcome.Partial
            }
            ClassificationResult.Retryable -> ProcessingOutcome.Retryable
            is ClassificationResult.Terminal -> {
                setFailure(jobId, "AI_CONFIGURATION_ERROR")
                ProcessingOutcome.Terminal
            }
        }
    }

    private fun candidateSnapshot(jobId: UUID): CandidateSnapshot = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val existing = c.prepareStatement("select candidate_snapshot_json from analysis_jobs where id=? for update").use { s ->
                s.setObject(1,jobId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
            }
            val result = if (existing != null) {
                val json = Json.parseToJsonElement(existing).jsonObject
                CandidateSnapshot(
                    json.getValue("categories").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    json.getValue("purposes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    json["category_labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                    json["purpose_labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            } else {
                val fresh = candidatesForJob(jobId)
                require(fresh.categoryIds.isNotEmpty() && fresh.purposeIds.size <= 10)
                require(fresh.categoryLabels.keys.all { it in fresh.categoryIds })
                require(fresh.purposeLabels.keys.all { it in fresh.purposeIds })
                val json = JsonObject(mapOf(
                    "categories" to JsonArray(fresh.categoryIds.sorted().map(::JsonPrimitive)),
                    "purposes" to JsonArray(fresh.purposeIds.sorted().map(::JsonPrimitive)),
                    "category_labels" to JsonObject(fresh.categoryLabels.mapValues { JsonPrimitive(it.value) }),
                    "purpose_labels" to JsonObject(fresh.purposeLabels.mapValues { JsonPrimitive(it.value) }),
                )).toString()
                c.prepareStatement("update analysis_jobs set candidate_snapshot_json=? where id=?").use { s ->
                    s.setString(1,json); s.setObject(2,jobId); s.executeUpdate()
                }
                fresh
            }
            c.commit()
            result
        } catch (error: Exception) { c.rollback(); throw error }
    }

    private fun saveAssignment(jobId: UUID, result: ClassificationResult.Assigned) {
        dataSource.connection.use { c -> c.prepareStatement(
            """update analysis_jobs j set pending_category_id=?,pending_purpose_id=?,pending_failure_code=null
               from wishlist_items i where j.wishlist_item_id=i.id and j.id=? and j.stage in ('GENERAL_RUNNING','BROWSER_RUNNING')
                 and i.lifecycle_status='ACTIVE'""",
        ).use { s -> s.setString(1,result.categoryId); s.setString(2,result.purposeId); s.setObject(3,jobId); check(s.executeUpdate()==1) } }
    }

    private fun setFailure(jobId: UUID, code: String) {
        dataSource.connection.use { c -> c.prepareStatement(
            """update analysis_jobs j set pending_failure_code=? from wishlist_items i
               where j.wishlist_item_id=i.id and j.id=? and j.stage in ('GENERAL_RUNNING','BROWSER_RUNNING') and i.lifecycle_status='ACTIVE'""",
        ).use { s -> s.setString(1,code); s.setObject(2,jobId); s.executeUpdate() } }
    }
}
