package app.duplicates

import app.analysis.ProcessingOutcome
import app.category.CategoryRef
import app.common.FieldChange
import app.http.ApiJson
import app.http.WishlistItemViewMapper
import app.media.MediaUrls
import app.testutil.AnalysisPipeline
import app.testutil.analysisSql
import app.testutil.identifiedPage
import app.testutil.itemText
import app.testutil.withAnalysisDatabase
import app.wishlist.GetWishlistItemService
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationService
import app.wishlist.ReadQuery
import app.wishlist.ReadResult
import app.wishlist.ReadScope
import app.wishlist.ReadWindow
import app.wishlist.ReanalysisService
import app.wishlist.WishlistReadService
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B9b: pair bases are stored at finish; kinds and counts follow the live analysis state. */
class DuplicateDetectionTest {
    private val owner = UUID.randomUUID()
    private val shop1 = "https://shop.example/speedgoat"
    private val shop2 = "https://other.example/hoka-speedgoat-6"
    /** Same site as [shop1]; a declared canonical is adopted only on the same registrable domain. */
    private val shop1Sale = "https://shop.example/sale/speedgoat"

    /** "new>existing status bases version" rows of an owner, sorted for stable assertions. */
    private fun pairs(source: DataSource, owner: UUID = this.owner): List<String> = source.connection.use { c ->
        c.prepareStatement("""select new_item_id, existing_item_id, status, array_to_string(bases, ','), version
            from duplicate_candidates where owner_id=? order by new_item_id, existing_item_id""").use { s ->
            s.setObject(1, owner)
            s.executeQuery().use { r -> buildList { while (r.next()) add("${r.getString(1)}>${r.getString(2)} ${r.getString(3)} ${r.getString(4)} ${r.getInt(5)}") } }
        }
    }

    private fun pair(new: UUID, existing: UUID, status: String, bases: String, version: Int = 1) = "$new>$existing $status $bases $version"

    private fun counts(source: DataSource, item: UUID): DuplicateCounts? = GetWishlistItemService(source).get(owner, item)!!.duplicateCounts

    private fun fail(source: DataSource, item: UUID, canonical: Boolean = true) = analysisSql(source,
        "update wishlist_items set analysis_status='FAILED_RETRYABLE'${if (canonical) "" else ",canonical_url=null"} where id='$item'")

    private fun reanalyze(source: DataSource, item: UUID) = ReanalysisService(source).reanalyze(owner, item, UUID.randomUUID())

    @Test fun `state matrix of existing items`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply {
            pages[shop1] = identifiedPage("Speedgoat 6", gtin = "4006381333931", mpn = "1123190")
            pages[shop2] = identifiedPage("HOKA Speedgoat 6", gtin = "4006381333931", mpn = "1123190")
        }
        // READY by canonical (same URL, served from the cache) and by GTIN + brand/MPN (another seller).
        val ready = pipeline.saveAndRun(owner, shop1)
        val sameUrl = pipeline.saveAndRun(owner, shop1)
        val otherSeller = pipeline.saveAndRun(owner, shop2)
        assertEquals(pair(sameUrl, ready, "OPEN", "CANONICAL_URL,GTIN,BRAND_MPN,SOURCE_URL"), pairs(source).single { it.startsWith("$sameUrl>") })
        assertTrue(pair(otherSeller, ready, "OPEN", "GTIN,BRAND_MPN") in pairs(source))
        assertEquals(DuplicateCounts(match = 2, sameUrl = 0, awaitingAnalysis = 0), counts(source, otherSeller))

        // PARTIAL existing still matches by what was extracted.
        val partialOwner = UUID.randomUUID()
        pipeline.classify = { _, _ -> ProcessingOutcome.Partial }
        val partial = pipeline.saveAndRun(partialOwner, shop2)
        assertEquals("PARTIAL", itemText(source, partial, "analysis_status"))
        pipeline.classify = AnalysisPipeline(source).classify
        val afterPartial = pipeline.saveAndRun(partialOwner, shop1)
        assertEquals(listOf(pair(afterPartial, partial, "OPEN", "GTIN,BRAND_MPN")), pairs(source, partialOwner))
        assertEquals(1, GetWishlistItemService(source).get(partialOwner, afterPartial)!!.duplicateCounts!!.match)
    }

    @Test fun `failed and processing existing items get a url notice or wait`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val failed = pipeline.save(owner, shop1).also { fail(source, it, canonical = false) }
        val processing = pipeline.save(owner, "$shop1?utm_source=talk")
        val new = pipeline.saveAndRun(owner, shop1)
        assertEquals(DuplicateCounts(match = 0, sameUrl = 1, awaitingAnalysis = 1), counts(source, new))
        assertTrue(pair(new, failed, "OPEN", "SOURCE_URL") in pairs(source))
        assertTrue(pair(new, processing, "OPEN", "SOURCE_URL") in pairs(source))
        assertNull(counts(source, failed))
    }

    @Test fun `a ready existing item related only by share url is hidden`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source)
        val short = "https://s.example/abc"
        pipeline.redirects[short] = shop1
        pipeline.pages[shop1] = identifiedPage("Speedgoat 6")
        val existing = pipeline.saveAndRun(owner, short)
        // The short link now points at another product; the cache would return the old one, so drop it.
        analysisSql(source, "delete from product_url_aliases"); analysisSql(source, "update wishlist_items set product_cache_id=null")
        analysisSql(source, "delete from products")
        pipeline.redirects[short] = shop2
        pipeline.pages[shop2] = identifiedPage("Other shoe", brand = "OTHER")
        val new = pipeline.saveAndRun(owner, short)
        assertEquals(listOf(pair(new, existing, "OPEN", "SOURCE_URL")), pairs(source))
        assertNull(counts(source, new))
    }

    @Test fun `an older item finishing later creates the pair in creation order`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val older = pipeline.save(owner, shop1)
        val newer = pipeline.save(owner, shop1)
        pipeline.run(newer)
        assertEquals(DuplicateCounts(0, 0, 1), counts(source, newer))
        pipeline.run(older)
        assertEquals(listOf(pair(newer, older, "OPEN", "CANONICAL_URL,SOURCE_URL", version = 2)), pairs(source))
        assertEquals(DuplicateCounts(1, 0, 0), counts(source, newer))
        assertNull(counts(source, older))
    }

    @Test fun `kinds follow analysis state changes outside finish`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val existing = pipeline.save(owner, shop1)
        val new = pipeline.saveAndRun(owner, shop1)
        assertEquals(DuplicateCounts(0, 0, 1), counts(source, new))
        // Exhaustion or lease recovery fails the item without a finish; the stored pair is unchanged.
        val before = pairs(source)
        fail(source, existing, canonical = false)
        assertEquals(DuplicateCounts(0, 1, 0), counts(source, new))
        assertEquals(before, pairs(source))
        // Reanalysis puts it back to PROCESSING: waiting again, then a match once it finishes.
        reanalyze(source, existing)
        assertEquals(DuplicateCounts(0, 0, 1), counts(source, new))
        pipeline.run(existing)
        assertEquals(DuplicateCounts(1, 0, 0), counts(source, new))
        // The new item itself failing hides every pair.
        fail(source, new)
        assertNull(counts(source, new))
    }

    @Test fun `pairs are withdrawn when identity no longer matches and reopened when it does`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply {
            pages[shop1] = identifiedPage("Speedgoat 6", gtin = "4006381333931")
            pages[shop2] = identifiedPage("Speedgoat 6", gtin = "4006381333931")
        }
        val existing = pipeline.saveAndRun(owner, shop1)
        val new = pipeline.saveAndRun(owner, shop2)
        assertEquals(listOf(pair(new, existing, "OPEN", "GTIN")), pairs(source))
        pipeline.pages[shop1] = identifiedPage("Speedgoat 6")
        fail(source, existing); reanalyze(source, existing); pipeline.run(existing)
        assertEquals(listOf(pair(new, existing, "WITHDRAWN", "GTIN", version = 2)), pairs(source))
        assertNull(counts(source, new))
        pipeline.pages[shop1] = identifiedPage("Speedgoat 6", gtin = "4006381333931")
        fail(source, existing); reanalyze(source, existing); pipeline.run(existing)
        assertEquals(listOf(pair(new, existing, "OPEN", "GTIN", version = 3)), pairs(source))
    }

    @Test fun `decided pairs survive reanalysis unchanged`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6"); pages[shop1Sale] = identifiedPage("Speedgoat 6", canonical = shop1) }
        val existing = pipeline.saveAndRun(owner, shop1)
        val new = pipeline.saveAndRun(owner, shop1Sale)
        assertEquals(listOf(pair(new, existing, "OPEN", "CANONICAL_URL,SOURCE_URL")), pairs(source))
        analysisSql(source, "update duplicate_candidates set status='DECIDED',decision='KEEP_BOTH',decided_at=clock_timestamp(),version=2")
        val before = pairs(source)
        pipeline.pages[shop1Sale] = identifiedPage("Speedgoat 6")
        for (item in listOf(existing, new)) { fail(source, item); reanalyze(source, item); pipeline.run(item) }
        assertEquals(before, pairs(source))
        assertNull(counts(source, new))
    }

    @Test fun `several candidates and a chain of saves`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val (a, b, c) = List(3) { pipeline.saveAndRun(owner, shop1) }
        val bases = "CANONICAL_URL,SOURCE_URL"
        assertEquals(listOf(pair(b, a, "OPEN", bases), pair(c, a, "OPEN", bases), pair(c, b, "OPEN", bases)).sorted(), pairs(source).sorted())
        assertEquals(DuplicateCounts(2, 0, 0), counts(source, c))
        assertEquals(DuplicateCounts(1, 0, 0), counts(source, b))
        assertNull(counts(source, a))
    }

    @Test fun `other owners never pair`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6", gtin = "4006381333931") }
        val mine = pipeline.saveAndRun(owner, shop1)
        val other = UUID.randomUUID()
        val theirs = pipeline.saveAndRun(other, shop1)
        assertEquals(emptyList(), pairs(source))
        assertEquals(emptyList(), pairs(source, other))
        assertNull(counts(source, mine))
        assertNull(GetWishlistItemService(source).get(other, theirs)!!.duplicateCounts)
    }

    @Test fun `archived and deleted items are excluded`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val archived = pipeline.saveAndRun(owner, shop1).also { analysisSql(source, "update wishlist_items set lifecycle_status='ARCHIVED' where id='$it'") }
        val deleted = pipeline.saveAndRun(owner, shop1).also { ItemMutationService(source).delete(owner, it) }
        val new = pipeline.saveAndRun(owner, shop1)
        assertTrue(pairs(source).none { it.startsWith("$new>") }, "no pair with inactive items")
        val kept = pipeline.saveAndRun(owner, shop1)
        assertEquals(DuplicateCounts(1, 0, 0), counts(source, kept))
        ItemMutationService(source).delete(owner, new)
        assertNull(counts(source, kept), "a pair whose other item was deleted is no longer counted")
        assertTrue(listOf(archived, deleted).all { item -> pairs(source).none { it.contains(item.toString()) } })
    }

    @Test fun `user edits of name and brand do not change identity`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply {
            pages[shop1] = identifiedPage("Speedgoat 6", mpn = "1123190"); pages[shop2] = identifiedPage("Speedgoat 6", mpn = "1123190")
        }
        val existing = pipeline.saveAndRun(owner, shop1)
        val version = itemText(source, existing, "version")!!.toInt()
        ItemMutationService(source).edit(owner, existing, version, ItemChanges(name = FieldChange.Set("내 신발"), brand = FieldChange.Set("다른 브랜드")))
        val new = pipeline.saveAndRun(owner, shop2)
        assertEquals(listOf(pair(new, existing, "OPEN", "BRAND_MPN")), pairs(source))
    }

    @Test fun `rows saved before normalized share urls are compared by normalizing in code`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val legacy = pipeline.save(owner, "$shop1#top")
        analysisSql(source, "update wishlist_items set normalized_source_url=null where id='$legacy'")
        fail(source, legacy, canonical = false)
        val new = pipeline.saveAndRun(owner, shop1)
        assertEquals(listOf(pair(new, legacy, "OPEN", "SOURCE_URL")), pairs(source))
        assertEquals(DuplicateCounts(0, 1, 0), counts(source, new))
    }

    @Test fun `counts reach detail and list cards and are omitted when zero`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[shop1] = identifiedPage("Speedgoat 6") }
        val first = pipeline.saveAndRun(owner, shop1)
        val second = pipeline.saveAndRun(owner, shop1)
        fun json(item: app.wishlist.WishlistItem) = ApiJson.encodeToString(app.http.WishlistItemDto.serializer(), WishlistItemViewMapper.map(item, MediaUrls(emptyMap())))
        val detail = json(GetWishlistItemService(source).get(owner, second)!!)
        assertTrue(""""duplicateCandidates":{"matchCount":1,"sameUrlCount":0,"awaitingAnalysisCount":0}""" in detail, detail)
        assertFalse("duplicateCandidates" in json(GetWishlistItemService(source).get(owner, first)!!))
        val cards = (WishlistReadService(source).read(owner, ReadQuery(ReadScope.Category(CategoryRef.parse("C026")!!), ReadWindow.Page(10))) as ReadResult.Success)
            .page.items.associateBy { it.id }
        assertEquals(DuplicateCounts(1, 0, 0), cards.getValue(second).duplicateCounts)
        assertTrue(""""matchCount":1""" in json(cards.getValue(second)))
        assertNull(cards.getValue(first).duplicateCounts)
    }

    @Test fun `an existing item that never read its page gets a url notice for an exact re-save`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source)
        val existing = pipeline.saveAndRun(owner, shop1)                        // 404: PARTIAL without a canonical
        assertEquals("PARTIAL", itemText(source, existing, "analysis_status"))
        assertNull(itemText(source, existing, "canonical_url"))
        pipeline.pages[shop1] = identifiedPage("Speedgoat 6")
        val new = pipeline.saveAndRun(owner, shop1)
        assertEquals(listOf(pair(new, existing, "OPEN", "SOURCE_URL")), pairs(source))
        assertEquals(DuplicateCounts(0, 1, 0), counts(source, new))
    }
}
