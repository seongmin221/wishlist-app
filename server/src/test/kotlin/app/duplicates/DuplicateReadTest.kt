package app.duplicates

import app.testutil.ItemSeed
import app.testutil.insertPurpose
import app.testutil.seedAnalyzedItem
import app.testutil.seedPair
import app.testutil.setCreatedAt
import app.testutil.withAnalysisDatabase
import app.wishlist.ItemMutationService
import app.wishlist.PurposeImpact
import app.wishlist.ReadPosition
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B9c DUP-01 on a real database: derived kinds, deletion impact, keyset pages and owner isolation. */
class DuplicateReadTest {
    private val owner = UUID.randomUUID()

    @Test fun `kinds and allowed decisions follow live states`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val ready = seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:03Z") }
        val failed = seedAnalyzedItem(source, owner, ItemSeed(analysis = "FAILED_TERMINAL", review = "NOT_REQUIRED", category = null,
            categorySource = null, missingReason = "EXTRACTION_UNRESOLVED")).also { setCreatedAt(source, it, "2026-10-01T00:00:02Z") }
        val processing = seedAnalyzedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"))
            .also { setCreatedAt(source, it, "2026-10-01T00:00:01Z") }
        val hidden = seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") }
        seedPair(source, owner, new, ready); seedPair(source, owner, new, failed, "SOURCE_URL")
        seedPair(source, owner, new, processing, "SOURCE_URL"); seedPair(source, owner, new, hidden, "SOURCE_URL")
        val page = DuplicateReadService(source).list(owner, new, 20, null)
        assertEquals(listOf(ready, failed, processing), page.candidates.map { it.existingItem.id })
        assertEquals(listOf(DuplicateKind.MATCH, DuplicateKind.SAME_URL, DuplicateKind.AWAITING_ANALYSIS), page.candidates.map { it.kind })
        assertEquals(listOf(DuplicateBasis.CANONICAL_URL, DuplicateBasis.SOURCE_URL), page.candidates[0].bases)
        assertEquals(DuplicateDecision.entries, page.candidates[0].allowedDecisions)
        assertEquals(DuplicateDecision.entries, page.candidates[1].allowedDecisions)
        assertEquals(emptyList(), page.candidates[2].allowedDecisions)
        assertEquals(3, page.totalCount)
        assertNull(page.next)
        assertEquals(DuplicateCounts(1, 1, 1), page.item.duplicateCounts, "the page and the item counts agree")
    }

    @Test fun `both sides carry their deletion impact`() = withAnalysisDatabase { source ->
        val purpose = insertPurpose(source, owner, "가을 트레일 러닝", null)
        val new = seedAnalyzedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val existing = seedAnalyzedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER"))
            .also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") }
        seedPair(source, owner, new, existing)
        val page = DuplicateReadService(source).list(owner, new, 20, null)
        assertEquals(PurposeImpact(purpose, "가을 트레일 러닝", 2), page.item.deletionImpact!!.purpose)
        assertEquals(PurposeImpact(purpose, "가을 트레일 러닝", 2), page.candidates.single().existingItem.deletionImpact!!.purpose)
    }

    @Test fun `same creation time pages by id without gaps or repeats`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val existing = List(5) { seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") } }
        existing.forEach { seedPair(source, owner, new, it) }
        val service = DuplicateReadService(source)
        val seen = mutableListOf<UUID>()
        var after: ReadPosition? = null
        val sizes = mutableListOf<Int>()
        do {
            val page = service.list(owner, new, 2, after)
            assertEquals(5, page.totalCount)
            sizes += page.candidates.size; seen += page.candidates.map { it.existingItem.id }
            after = page.next
        } while (after != null)
        assertEquals(listOf(2, 2, 1), sizes)
        assertEquals(existing.sortedByDescending { it.toString() }, seen)
    }

    @Test fun `pairs disappear when the other item is deleted`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val (a, b) = List(2) { seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") } }
        seedPair(source, owner, new, a); seedPair(source, owner, new, b)
        ItemMutationService(source).delete(owner, a)
        val page = DuplicateReadService(source).list(owner, new, 20, null)
        assertEquals(listOf(b), page.candidates.map { it.existingItem.id })
        assertEquals(1, page.totalCount)
    }

    @Test fun `archived new items list nothing and missing or foreign items are not found`() = withAnalysisDatabase { source ->
        val archived = seedAnalyzedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED"))
        seedPair(source, owner, archived, seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") })
        val page = DuplicateReadService(source).list(owner, archived, 20, null)
        assertEquals(0, page.totalCount); assertTrue(page.candidates.isEmpty())
        val deleted = seedAnalyzedItem(source, owner, ItemSeed(lifecycle = "DELETED"))
        val foreign = seedAnalyzedItem(source, UUID.randomUUID())
        for (id in listOf(deleted, foreign, UUID.randomUUID())) {
            assertEquals("WISHLIST_ITEM_NOT_FOUND", assertFailsWith<DuplicateException> { DuplicateReadService(source).list(owner, id, 20, null) }.code)
        }
        assertNotNull(page.item)
    }
}
