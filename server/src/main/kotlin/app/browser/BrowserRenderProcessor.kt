package app.browser

import app.extraction.Metadata
import java.util.UUID
import javax.sql.DataSource

class BrowserRenderProcessor(
    private val dataSource: DataSource,
    private val gateway: (String) -> Metadata?,
) {
    fun render(jobId: UUID): Metadata? {
        val sourceUrl = dataSource.connection.use { connection ->
            connection.prepareStatement("select i.source_url from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id where j.id=? and j.stage='BROWSER_RUNNING' and i.lifecycle_status='ACTIVE'").use {
                it.setObject(1, jobId)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return null
        return gateway(sourceUrl)
    }
}
