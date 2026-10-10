package app.analysis

import app.ai.ClassificationResult
import app.browser.BrowserWorkerService
import app.extraction.Metadata
import app.http.WishlistItemViewMapper
import app.testutil.*
import app.wishlist.GetWishlistItemService
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataPersistenceTest {
    private val productPage = Metadata("Neo Daichi", "desc", "https://example.com/i.jpg", "https://example.com/p/1",
        "Mizuno", BigDecimal("129000.50"), "KRW", "Mizuno Store")

    private fun columns(source: javax.sql.DataSource, job: FinishJob) = analysisScalar(source, """
        select jsonb_build_array(product_brand,product_price,product_currency,merchant_name,metadata_checked_at is not null,
            metadata_checked_at=updated_at)::text from wishlist_items where id='${job.itemId}'""")

    @Test fun `pending product fields are cleared by a general reclaim and kept by a browser reclaim`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = newFinishJob(source, lane)
            val claim = claimJob(source, job.jobId, lane)
            AnalysisPendingResultRepository(source).saveMetadata(claim, productPage)
            assertEquals("""["Mizuno", 129000.5000, "KRW", "Mizuno Store"]""", analysisScalar(source,
                "select jsonb_build_array(pending_brand,pending_price,pending_currency,pending_merchant)::text from analysis_jobs where id='${job.jobId}'"))
            analysisSql(source, "update analysis_jobs set stage='${lane.name}_PENDING',lease_until=null,execution_token=null,claimed_item_version=null where id='${job.jobId}'")
            claimJob(source, job.jobId, lane)
            val expected = if (lane == AnalysisLane.GENERAL) """[null, null, null, null]""" else """["Mizuno", 129000.5000, "KRW", "Mizuno Store"]"""
            assertEquals(expected, analysisScalar(source,
                "select jsonb_build_array(pending_brand,pending_price,pending_currency,pending_merchant)::text from analysis_jobs where id='${job.jobId}'"), lane.name)
        }
    }

    @Test fun `successful finish stores product fields and the finish database time and the API returns them`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        val claim = claimJob(source, job.jobId)
        AnalysisPendingResultRepository(source).saveMetadata(claim, productPage)
        AnalysisPendingResultRepository(source).saveAssignment(claim, ClassificationResult.Assigned("C006", null))
        assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete))
        assertEquals("""["Mizuno", 129000.5000, "KRW", "Mizuno Store", true, true]""", columns(source, job))
        assertEquals("2", analysisScalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        val owner = java.util.UUID.fromString(analysisScalar(source, "select owner_id from wishlist_items where id='${job.itemId}'"))
        val product = WishlistItemViewMapper.map(assertNotNull(GetWishlistItemService(source).get(owner, job.itemId))).product
        assertEquals("Mizuno", product.brand); assertEquals(BigDecimal("129000.5000"), product.price); assertEquals("KRW", product.currency)
        assertEquals("Mizuno Store", product.merchant); assertNotNull(product.metadataCheckedAt)
    }

    @Test fun `browser partial after general needs browser has no page metadata and records no check time`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        val general = claimJob(source, job.jobId)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(general, ProcessingOutcome.NeedsBrowser))
        BrowserWorkerService(source, { null }, { _, _ -> error("not classified") }).runBrowser(job.jobId, 1)
        assertEquals("PARTIAL", analysisScalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
        assertNull(analysisScalar(source, "select metadata_checked_at from wishlist_items where id='${job.itemId}'"))
    }

    @Test fun `partial with page metadata records check time and user brand override is preserved`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.GENERAL)
        analysisSql(source, "update wishlist_items set product_brand='My brand',user_override_fields=array['BRAND'] where id='${job.itemId}'")
        val claim = claimJob(source, job.jobId)
        AnalysisPendingResultRepository(source).saveMetadata(claim, productPage)
        AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Partial)
        assertEquals("""["My brand", 129000.5000, "KRW", "Mizuno Store", true, true]""", columns(source, job))
    }
}
