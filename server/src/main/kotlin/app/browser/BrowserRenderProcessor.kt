package app.browser

import app.analysis.AnalysisClaim
import app.analysis.AnalysisPendingResultRepository
import app.extraction.Metadata
import javax.sql.DataSource

class BrowserRenderProcessor(dataSource: DataSource, private val gateway: (String) -> Metadata?) {
    private val pending = AnalysisPendingResultRepository(dataSource)

    fun render(claim: AnalysisClaim): Metadata? {
        val sourceUrl = pending.sourceUrl(claim) ?: return null
        val metadata = gateway(sourceUrl) ?: return null
        return if (pending.saveMetadata(claim, metadata)) metadata else null
    }
}
