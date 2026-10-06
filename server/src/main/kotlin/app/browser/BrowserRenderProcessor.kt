package app.browser

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.extraction.Metadata
import javax.sql.DataSource

class BrowserRenderProcessor(dataSource: DataSource, private val gateway: (String) -> Metadata?) {
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun render(claim: AnalysisClaim): Metadata? {
        val sourceUrl = pending.sourceUrl(claim) ?: return null
        // The worker performs the sole guarded metadata write before classification.
        return gateway(sourceUrl)
    }
}
