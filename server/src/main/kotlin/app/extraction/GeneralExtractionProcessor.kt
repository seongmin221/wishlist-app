package app.extraction

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.analysis.ProcessingOutcome
import javax.sql.DataSource

class GeneralExtractionProcessor(
    dataSource: DataSource,
    private val extract: (String) -> ExtractionResult,
    private val classify: (AnalysisClaim, Metadata) -> ProcessingOutcome,
) {
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun process(claim: AnalysisClaim): ProcessingOutcome {
        val sourceUrl = pending.sourceUrl(claim) ?: return ProcessingOutcome.Stale
        val result = try { extract(sourceUrl) } catch (_: UnsafeUrlException) {
            return ProcessingOutcome.Terminal
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
