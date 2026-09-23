package app.extraction

import app.analysis.ProcessingOutcome
import java.util.UUID
import javax.sql.DataSource

class GeneralExtractionProcessor(
    private val dataSource: DataSource,
    private val extract: (String) -> ExtractionResult,
    private val classify: (UUID, Metadata) -> ProcessingOutcome,
) {
    fun process(jobId: UUID): ProcessingOutcome {
        val sourceUrl = dataSource.connection.use { connection ->
            connection.prepareStatement("select i.source_url from analysis_jobs j join wishlist_items i on i.id=j.wishlist_item_id where j.id=? and j.stage='GENERAL_RUNNING' and i.lifecycle_status='ACTIVE'").use {
                it.setObject(1, jobId)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else return ProcessingOutcome.Terminal }
            }
        }
        val result = try { extract(sourceUrl) } catch (_: UnsafeUrlException) { return ProcessingOutcome.Terminal }
        return when (result) {
            is ExtractionResult.Complete -> {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """update analysis_jobs j set pending_product_name=?, pending_product_description=?, pending_product_image_url=?, pending_canonical_url=?
                           from wishlist_items i where j.wishlist_item_id=i.id and j.id=? and j.stage='GENERAL_RUNNING' and i.lifecycle_status='ACTIVE'""",
                    ).use {
                        it.setString(1, result.metadata.title)
                        it.setString(2, result.metadata.description)
                        it.setString(3, result.metadata.imageUrl)
                        it.setString(4, result.metadata.canonicalUrl)
                        it.setObject(5, jobId)
                        it.executeUpdate()
                    }
                }
                classify(jobId, result.metadata)
            }
            ExtractionResult.NeedsBrowser -> ProcessingOutcome.NeedsBrowser
            ExtractionResult.Partial -> ProcessingOutcome.Partial
        }
    }
}
