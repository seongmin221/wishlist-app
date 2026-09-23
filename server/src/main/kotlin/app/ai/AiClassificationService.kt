package app.ai

import app.analysis.ProcessingOutcome
import app.budget.LlmBudgetService
import app.budget.ReserveResult
import app.extraction.Metadata
import java.util.UUID
import javax.sql.DataSource

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
        val candidates = candidatesForJob(jobId)
        val reservation = when (val result = budget.reserveBeforeCall(jobId, claim, UUID.randomUUID())) {
            is ReserveResult.Reserved -> result.reservation
            ReserveResult.Exceeded -> {
                setFailure(jobId, "AI_BUDGET_EXCEEDED")
                return ProcessingOutcome.Partial
            }
        }
        budget.markInFlight(reservation.id)
        val result = gateway(listOfNotNull(metadata.title, metadata.description).joinToString(" "), candidates)
        if (result.inputTokens != null && result.outputTokens != null && result.inputTokens in 0..1000 && result.outputTokens in 0..80) {
            budget.settle(reservation.id, result.inputTokens, result.outputTokens)
        } else if (result.classification !is ClassificationResult.Retryable) {
            budget.settleMaximum(reservation.id)
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
