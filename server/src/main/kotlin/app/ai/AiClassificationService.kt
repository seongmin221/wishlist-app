package app.ai

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.analysis.ProcessingOutcome
import app.budget.LlmBudgetService
import app.budget.ReserveResult
import app.extraction.Metadata
import java.util.UUID
import javax.sql.DataSource

class AiClassificationService(
    dataSource: DataSource,
    private val budget: LlmBudgetService,
    private val candidatesForJob: (UUID) -> CandidateSnapshot,
    private val gateway: (String, CandidateSnapshot) -> GatewayResponse,
) {
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun classify(claim: AnalysisClaim, metadata: Metadata): ProcessingOutcome {
        val candidates = pending.candidateSnapshot(claim, candidatesForJob) ?: return ProcessingOutcome.Stale
        val reservation = when (val result = budget.reserveBeforeCall(claim, UUID.randomUUID())) {
            is ReserveResult.Reserved -> result.reservation
            ReserveResult.Stale -> return ProcessingOutcome.Stale
            ReserveResult.Exceeded -> return failure(claim, "AI_BUDGET_EXCEEDED", ProcessingOutcome.Partial)
        }
        // A stale RESERVED reservation is released by existing lease reconciliation.
        if (!pending.isCurrent(claim)) return ProcessingOutcome.Stale
        budget.markInFlight(reservation.id)
        val result = gateway(listOfNotNull(metadata.title, metadata.description).joinToString(" "), candidates)
        val usageWithinLimit = result.inputTokens != null && result.outputTokens != null && result.inputTokens in 0..2000 && result.outputTokens in 0..80
        if (usageWithinLimit) {
            budget.settle(reservation.id, result.inputTokens, result.outputTokens)
        } else if (result.classification !is ClassificationResult.Retryable) {
            budget.settleMaximum(reservation.id)
        }
        // Actual usage is accounted for even when this execution lost ownership during the call.
        if (!pending.isCurrent(claim)) return ProcessingOutcome.Stale
        if (result.classification is ClassificationResult.Assigned && !usageWithinLimit)
            return failure(claim, "AI_USAGE_OUT_OF_RANGE", ProcessingOutcome.Partial)
        return when (val classification = result.classification) {
            is ClassificationResult.Assigned -> {
                if (classification.categoryId !in candidates.categoryIds ||
                    (classification.purposeId != null && classification.purposeId !in candidates.purposeIds)) {
                    failure(claim, "AI_INVALID_CANDIDATE", ProcessingOutcome.Partial)
                } else if (pending.saveAssignment(claim, classification)) ProcessingOutcome.Complete else ProcessingOutcome.Stale
            }
            ClassificationResult.Abstained -> failure(claim, "AI_ABSTAINED", ProcessingOutcome.Partial)
            is ClassificationResult.Unusable -> failure(claim, "AI_UNUSABLE_RESPONSE", ProcessingOutcome.Partial)
            ClassificationResult.Retryable -> if (pending.isCurrent(claim)) ProcessingOutcome.Retryable else ProcessingOutcome.Stale
            is ClassificationResult.Terminal -> failure(claim, "AI_CONFIGURATION_ERROR", ProcessingOutcome.Terminal)
        }
    }

    private fun failure(claim: AnalysisClaim, code: String, outcome: ProcessingOutcome): ProcessingOutcome =
        if (pending.saveFailure(claim, code)) outcome else ProcessingOutcome.Stale
}
