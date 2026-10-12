package app.extraction

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.analysis.ProcessingOutcome
import app.wishlist.AnalysisFailureCode
import javax.sql.DataSource

class GeneralExtractionProcessor(
    dataSource: DataSource,
    private val extract: (String) -> ExtractionResult,
    private val classify: (AnalysisClaim, Metadata) -> ProcessingOutcome,
) {
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun process(claim: AnalysisClaim): ProcessingOutcome {
        val sourceUrl = pending.sourceUrl(claim) ?: return ProcessingOutcome.Stale
        val result = try { extract(sourceUrl) } catch (_: DnsLookupFailed) {
            return ProcessingOutcome.Retryable
        } catch (_: UnsafeUrlException) {
            // Every unsafe or unsupported address shares the public BLOCKED_ADDRESS code.
            return if (pending.saveFailure(claim, AnalysisFailureCode.BLOCKED_ADDRESS)) ProcessingOutcome.Terminal else ProcessingOutcome.Stale
        }
        return when (result) {
            is ExtractionResult.Complete -> {
                if (!pending.saveMetadata(claim, result.metadata)) ProcessingOutcome.Stale
                else classify(claim, result.metadata)
            }
            // These are provisional outcomes with no DB write; finish owns the final guard.
            ExtractionResult.NeedsBrowser -> ProcessingOutcome.NeedsBrowser
            ExtractionResult.Partial -> ProcessingOutcome.Partial
        }
    }
}
