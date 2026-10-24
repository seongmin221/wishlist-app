package app.product

import app.ai.ClassificationResult
import app.analysis.AnalysisPendingResultRepository
import app.analysis.ProcessingOutcome
import app.common.FieldChange
import app.testutil.AnalysisPipeline
import app.testutil.analysisScalar
import app.testutil.analysisSql
import app.testutil.claimJob
import app.testutil.identifiedPage
import app.testutil.insertPurpose
import app.testutil.itemText
import app.testutil.seedV3Snapshot
import app.testutil.withAnalysisDatabase
import app.extraction.Metadata
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** B9a: shared READY cache reuse, per-owner classification and independent item snapshots on a real database. */
class ProductCacheTest {
    private val url = "https://shop.example/speedgoat"
    private val ownerA = UUID.randomUUID()
    private val ownerB = UUID.randomUUID()

    private fun products(source: javax.sql.DataSource) = analysisScalar(source, "select count(*) from products")

    @Test fun `same url with a new key is a new item served from the cache`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[url] = identifiedPage("Speedgoat 6") }
        val first = pipeline.saveAndRun(ownerA, url)
        assertEquals(1, pipeline.fetched.size)
        val key = UUID.randomUUID()
        val second = pipeline.save(ownerA, url, key).also(pipeline::run)
        val other = pipeline.saveAndRun(ownerB, url)
        assertEquals(1, pipeline.fetched.size, "cache hits skip the network")
        assertEquals(3, setOf(first, second, other).size)
        for (item in listOf(second, other)) {
            assertEquals("READY", itemText(source, item, "analysis_status"))
            assertEquals("Speedgoat 6", itemText(source, item, "product_name"))
            assertEquals(analysisScalar(source, "select id::text from products"), itemText(source, item, "product_cache_id"))
        }
        assertNull(itemText(source, first, "product_cache_id"), "a fresh fetch is not copied from the cache")
        val replay = CreateWishlistItemService(source).create(ownerA, key, url)
        assertEquals(second, assertIs<CreateResult.Replayed>(replay).itemId)
    }

    @Test fun `cache hits are classified for each owner`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[url] = identifiedPage("Speedgoat 6") }
        val purposes = mapOf(ownerA to insertPurpose(source, ownerA, "trail", null), ownerB to insertPurpose(source, ownerB, "gift", null))
        val categories = mapOf(ownerA to "C026", ownerB to "C006")
        val classified = mutableListOf<UUID>()
        pipeline.classify = { claim, _ ->
            classified += claim.ownerId
            seedV3Snapshot(source, claim, listOf(purposes.getValue(claim.ownerId)))
            analysisSql(source, """update analysis_jobs set candidate_snapshot_json=jsonb_set(candidate_snapshot_json::jsonb,'{categories}',
                '["${categories.getValue(claim.ownerId)}"]')::text where id='${claim.jobId}'""")
            check(AnalysisPendingResultRepository(source).saveAssignment(claim,
                ClassificationResult.Assigned(categories.getValue(claim.ownerId), purposes.getValue(claim.ownerId).toString(), true)))
            ProcessingOutcome.Complete
        }
        val a = pipeline.saveAndRun(ownerA, url)
        val b = pipeline.saveAndRun(ownerB, url)
        assertEquals(1, pipeline.fetched.size)
        assertEquals(listOf(ownerA, ownerB), classified)
        assertEquals("C026", itemText(source, a, "category_id"))
        assertEquals("C006", itemText(source, b, "category_id"))
        assertEquals(purposes[ownerA].toString(), itemText(source, a, "purpose_id"))
        assertEquals(purposes[ownerB].toString(), itemText(source, b, "purpose_id"))
    }

    @Test fun `item snapshots never follow the cache in either direction`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[url] = identifiedPage("Speedgoat 6", price = "199000") }
        pipeline.saveAndRun(ownerA, url)
        val copied = pipeline.saveAndRun(ownerB, url)
        // Another share URL resolves to the same canonical page with newer facts and refreshes the cache row.
        val other = "https://shop.example/speedgoat-sale"
        pipeline.pages[other] = identifiedPage("Speedgoat 6 (sale)", price = "149000", canonical = url)
        pipeline.saveAndRun(ownerA, other)
        assertEquals("Speedgoat 6 (sale)", analysisScalar(source, "select product_name from products"))
        assertEquals("Speedgoat 6", itemText(source, copied, "product_name"))
        assertEquals("199000.0000", itemText(source, copied, "product_price"))

        val before = analysisScalar(source, "select to_jsonb(p)::text from products p")
        val version = itemText(source, copied, "version")!!.toInt()
        ItemMutationService(source).edit(ownerB, copied, version, ItemChanges(name = FieldChange.Set("내 신발")))
        assertEquals("내 신발", itemText(source, copied, "product_name"))
        assertEquals(before, analysisScalar(source, "select to_jsonb(p)::text from products p"))
    }

    @Test fun `entries expire seven days after extraction`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[url] = identifiedPage("Speedgoat 6") }
        pipeline.saveAndRun(ownerA, url)
        analysisSql(source, "update products set extracted_at=clock_timestamp()-interval '6 days 23 hours'")
        pipeline.saveAndRun(ownerA, url)
        assertEquals(1, pipeline.fetched.size, "still fresh")
        analysisSql(source, "update products set extracted_at=clock_timestamp()-interval '7 days 1 second'")
        val refreshed = pipeline.saveAndRun(ownerA, url)
        assertEquals(2, pipeline.fetched.size, "expired entries are fetched again")
        assertNull(itemText(source, refreshed, "product_cache_id"))
        assertEquals("1", products(source))
    }

    @Test fun `only fresh complete extractions are cached and hits do not refresh them`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source)
        pipeline.saveAndRun(ownerA, "https://shop.example/missing")            // 404: Partial
        pipeline.pages["https://shop.example/js"] = app.testutil.MetadataFixtures.page()
        pipeline.saveAndRun(ownerA, "https://shop.example/js")                 // no title: NeedsBrowser
        pipeline.saveAndRun(ownerA, "https://blocked.example/p")               // private address: BLOCKED_ADDRESS
        assertEquals("0", products(source))

        pipeline.pages[url] = identifiedPage("Speedgoat 6")
        pipeline.saveAndRun(ownerA, url)
        val extracted = analysisScalar(source, "select extracted_at::text from products")
        val hit = pipeline.saveAndRun(ownerB, url)
        assertEquals(extracted, analysisScalar(source, "select extracted_at::text from products"))
        assertEquals("1", products(source))
        assertNotNull(itemText(source, hit, "product_cache_id"))
    }

    @Test fun `cache hit keeps the extraction time as the price check time`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[url] = identifiedPage("Speedgoat 6") }
        pipeline.saveAndRun(ownerA, url)
        analysisSql(source, "update products set extracted_at=clock_timestamp()-interval '3 days'")
        val hit = pipeline.saveAndRun(ownerB, url)
        assertEquals(analysisScalar(source, "select extracted_at::text from products"), itemText(source, hit, "metadata_checked_at"))
    }

    @Test fun `aliases find redirected and tracking-only variants of a share url`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source)
        val short = "https://s.example/abc"
        pipeline.redirects[short] = url
        pipeline.pages[url] = identifiedPage("Speedgoat 6")
        pipeline.saveAndRun(ownerA, short)
        assertEquals(listOf(short, url), pipeline.fetched)
        val again = pipeline.saveAndRun(ownerB, short)
        val tracked = pipeline.saveAndRun(ownerB, "$short?utm_source=chat")
        val direct = pipeline.saveAndRun(ownerB, "$url#reviews")
        assertEquals(2, pipeline.fetched.size)
        for (item in listOf(again, tracked, direct)) assertNotNull(itemText(source, item, "product_cache_id"))
        assertEquals(url, itemText(source, again, "canonical_url"))
        assertEquals("https://s.example/abc", itemText(source, tracked, "normalized_source_url"))
    }

    @Test fun `a stale claim writes neither pending metadata nor the cache`() = withAnalysisDatabase { source ->
        val item = CreateWishlistItemService(source).create(ownerA, UUID.randomUUID(), url).let { (it as CreateResult.Created).itemId }
        val claim = claimJob(source, UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'")))
        analysisSql(source, "update wishlist_items set version=version+1 where id='$item'")
        assertEquals(false, AnalysisPendingResultRepository(source).saveMetadata(claim, Metadata("Speedgoat 6", null, null, url)))
        assertEquals("0", products(source))
    }

    @Test fun `the browser lane caches what it rendered`() = withAnalysisDatabase { source ->
        val item = CreateWishlistItemService(source).create(ownerA, UUID.randomUUID(), url).let { (it as CreateResult.Created).itemId }
        val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
        analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true,attempt_count=1,first_attempt_at=clock_timestamp() where id='$job'")
        app.browser.BrowserWorkerService(source, { Metadata("Rendered shoe", null, null, url, gtin = "04006381333931") }, { claim, _ ->
            check(AnalysisPendingResultRepository(source).saveAssignment(claim, ClassificationResult.Assigned("C026", null)))
            ProcessingOutcome.Complete
        }).runBrowser(job, 1)
        assertEquals("Rendered shoe|04006381333931", analysisScalar(source, "select product_name||'|'||product_gtin from products"))
    }

    @Test fun `identifiers and the normalized share url are copied onto the item`() = withAnalysisDatabase { source ->
        val shared = "$url?utm_medium=share#top"
        val pipeline = AnalysisPipeline(source).apply {
            pages[shared] = identifiedPage("Speedgoat 6", gtin = "4006381333931", mpn = "1123190-BBLC")
        }
        val item = pipeline.saveAndRun(ownerA, shared)
        assertEquals(1, pipeline.fetched.size)
        assertEquals("04006381333931", itemText(source, item, "product_gtin"))
        assertEquals("hoka|1123190BBLC", itemText(source, item, "product_mpn_key"))
        assertEquals(url, itemText(source, item, "normalized_source_url"))
        val hit = pipeline.saveAndRun(ownerB, url)
        assertEquals("04006381333931", itemText(source, hit, "product_gtin"))
        assertEquals("hoka|1123190BBLC", itemText(source, hit, "product_mpn_key"))
        assertNotEquals(item, hit)
    }
}
