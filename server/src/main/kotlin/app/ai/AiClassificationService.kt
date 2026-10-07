package app.ai

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.analysis.ProcessingOutcome
import app.budget.LlmBudgetService
import app.budget.ReserveResult
import app.wishlist.AnalysisFailureCode
import app.budget.BudgetReservation
import app.extraction.Metadata
import java.util.UUID
import javax.sql.DataSource

class AiClassificationService private constructor(
    dataSource: DataSource,
    private val budget: LlmBudgetService,
    private val candidatesForJob: ((UUID) -> CandidateSnapshot)?,
    private val candidateProvider: CategoryCandidateProvider?,
    private val gateway: (String, CandidateSnapshot, () -> Unit) -> GatewayResponse,
) {
    constructor(dataSource:DataSource,budget:LlmBudgetService,supply:(UUID)->CandidateSnapshot,gateway:(String,CandidateSnapshot,()->Unit)->GatewayResponse)
        : this(dataSource,budget,supply,null,gateway)
    constructor(dataSource:DataSource,budget:LlmBudgetService,provider:CategoryCandidateProvider,gateway:(String,CandidateSnapshot,()->Unit)->GatewayResponse)
        : this(dataSource,budget,null,provider,gateway)
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun classify(claim: AnalysisClaim, metadata: Metadata): ProcessingOutcome {
        val candidates = (candidateProvider?.let { pending.candidateSnapshotWithConnection(claim,it::snapshot) }
            ?: if(candidateProvider==null) pending.candidateSnapshot(claim,requireNotNull(candidatesForJob)) else null)
            ?: return if (pending.isCurrent(claim)) ProcessingOutcome.Partial else ProcessingOutcome.Stale
        val reservation = when (val result = budget.reserveBeforeCall(claim, UUID.randomUUID())) {
            is ReserveResult.Reserved -> result.reservation
            ReserveResult.Stale -> return ProcessingOutcome.Stale
            ReserveResult.Exceeded -> return failure(claim, AnalysisFailureCode.AI_BUDGET_EXCEEDED, ProcessingOutcome.Partial)
        }
        var inFlight = false
        var notSent = false
        var originalFailure: Throwable? = null
        try {
            if (!pending.isCurrent(claim)) return ProcessingOutcome.Stale
            val result = gateway(listOfNotNull(metadata.title, metadata.description).joinToString(" "), candidates) {
                check(!inFlight) { "Gateway entered flight more than once" }
                budget.markInFlight(reservation.id)
                inFlight = true
            }
            if (!inFlight && (result.inputTokens != null || result.outputTokens != null ||
                    result.classification is ClassificationResult.Assigned || result.classification == ClassificationResult.Abstained)) {
                // A paid response without the flight handshake is never a free success.
                budget.markInFlight(reservation.id)
                inFlight = true
                budget.settleMaximum(reservation.id)
                error("Gateway returned paid response without entering flight")
            }
            return applyResponse(claim, candidates, reservation, result, inFlight)
        } catch (cause: LlmRequestNotSent) {
            originalFailure = cause
            notSent = true
            return ProcessingOutcome.Retryable
        } catch (cause: Throwable) {
            originalFailure = cause
            throw cause
        } finally {
            if (!inFlight || notSent) {
                try {
                    if (inFlight) budget.releaseUnsentInFlight(reservation.id) else budget.releaseReserved(reservation.id)
                } catch (cleanup: Throwable) {
                    val original = originalFailure
                    if (original != null && cleanup !== original) original.addSuppressed(cleanup)
                    throw original ?: cleanup
                }
            }
        }
    }

    private fun applyResponse(claim: AnalysisClaim, candidates: CandidateSnapshot, reservation: BudgetReservation,
        result: GatewayResponse, inFlight: Boolean): ProcessingOutcome {
        val usageWithinLimit = result.inputTokens != null && result.outputTokens != null && result.inputTokens in 0..2000 && result.outputTokens in 0..80
        if (inFlight && usageWithinLimit) {
            budget.settle(reservation.id, result.inputTokens, result.outputTokens)
        } else if (inFlight && result.classification !is ClassificationResult.Retryable) {
            budget.settleMaximum(reservation.id)
        }
        // Actual usage is accounted for even when this execution lost ownership during the call.
        if (result.classification is ClassificationResult.Assigned && !usageWithinLimit)
            return failure(claim, AnalysisFailureCode.AI_USAGE_OUT_OF_RANGE, ProcessingOutcome.Partial)
        return when (val classification = result.classification) {
            is ClassificationResult.Assigned -> {
                if (classification.categoryId !in candidates.categoryIds ||
                    (classification.purposeId != null && classification.purposeId !in candidates.purposeIds)) {
                    failure(claim, AnalysisFailureCode.AI_INVALID_CANDIDATE, ProcessingOutcome.Partial)
                } else if (pending.saveAssignment(claim, classification)) ProcessingOutcome.Complete else ProcessingOutcome.Stale
            }
            ClassificationResult.Abstained -> failure(claim, AnalysisFailureCode.AI_ABSTAINED, ProcessingOutcome.Partial)
            is ClassificationResult.Unusable -> failure(claim, AnalysisFailureCode.AI_UNUSABLE_RESPONSE, ProcessingOutcome.Partial)
            ClassificationResult.Retryable -> if (pending.isCurrent(claim)) ProcessingOutcome.Retryable else ProcessingOutcome.Stale
            is ClassificationResult.Terminal -> failure(claim, AnalysisFailureCode.AI_CONFIGURATION_ERROR, ProcessingOutcome.Terminal)
        }
    }

    private fun failure(claim: AnalysisClaim, code: AnalysisFailureCode, outcome: ProcessingOutcome): ProcessingOutcome =
        if (pending.saveFailure(claim, code)) outcome else ProcessingOutcome.Stale
}
