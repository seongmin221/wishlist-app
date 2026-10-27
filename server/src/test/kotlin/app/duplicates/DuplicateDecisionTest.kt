package app.duplicates

import app.testutil.ItemSeed
import app.testutil.analysisScalar
import app.testutil.analysisSql
import app.testutil.insertMedia
import app.testutil.insertPurpose
import app.testutil.itemColumns
import app.testutil.mediaColumns
import app.testutil.pairColumns
import app.testutil.purposeColumns
import app.testutil.seedAnalyzedItem
import app.testutil.seedPair
import app.testutil.setCreatedAt
import app.testutil.withAnalysisDatabase
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationService
import app.wishlist.ReanalysisService
import app.wishlist.ReviewDecision
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B9d DUP-02 on a real database: one transaction for the decision, the deletion and the receipt. */
class DuplicateDecisionTest {
    private val owner = UUID.randomUUID()

    private data class Pair3(val new: UUID, val existing: UUID, val pair: UUID)

    private fun pairOf(source: DataSource, newSeed: ItemSeed = ItemSeed(), existingSeed: ItemSeed = ItemSeed()): Pair3 {
        val new = seedAnalyzedItem(source, owner, newSeed)
        val existing = seedAnalyzedItem(source, owner, existingSeed).also { setCreatedAt(source, it, "2026-10-01T00:00:00Z") }
        return Pair3(new, existing, seedPair(source, owner, new, existing))
    }

    private fun request(pair: UUID, decision: DuplicateDecision, candidateVersion: Int = 1, itemVersion: Int? = 3) =
        DuplicateDecisionRequest(pair, decision, candidateVersion, if (decision == DuplicateDecision.KEEP_BOTH) null else itemVersion)

    private fun decide(source: DataSource, item: UUID, request: DuplicateDecisionRequest, key: UUID = UUID.randomUUID()) =
        DuplicateDecisionService(source).decide(owner, item, key, request)

    private fun conflict(source: DataSource, item: UUID, request: DuplicateDecisionRequest, key: UUID = UUID.randomUUID()) =
        assertFailsWith<DuplicateException> { decide(source, item, request, key) }

    private fun lifecycle(source: DataSource, item: UUID) = itemColumns(source, item, "lifecycle_status", "version")

    @Test fun `keep both records the decision and changes neither item`() = withAnalysisDatabase { source ->
        val (new, existing, pair) = pairOf(source)
        val before = listOf(new, existing).map { itemColumns(source, it, "version", "updated_at", "review_status") }
        val result = decide(source, new, request(pair, DuplicateDecision.KEEP_BOTH))
        assertEquals(pair, result.candidateId)
        assertEquals(DuplicateDecision.KEEP_BOTH, result.decision)
        assertEquals(2, result.candidateVersion)
        assertNull(result.deletedItemId)
        assertEquals(listOf(new, existing).sortedBy { it.toString() }, result.remainingItemIds)
        assertEquals(emptyList(), result.purposes)
        assertFalse(result.replayed)
        assertEquals("DECIDED KEEP_BOTH 2", pairColumns(source, pair))
        assertEquals(before, listOf(new, existing).map { itemColumns(source, it, "version", "updated_at", "review_status") })
        assertEquals(0, DuplicateReadService(source).list(owner, new, 20, null).totalCount)
    }

    @Test fun `delete new removes the new item like ITEM-05 and hides its other pairs`() = withAnalysisDatabase { source ->
        val purpose = insertPurpose(source, owner, "가을 트레일 러닝", null)
        val (new, existing, pair) = pairOf(source, ItemSeed(purpose = purpose, purposeSource = "AI"), ItemSeed(purpose = purpose, purposeSource = "USER"))
        val third = seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-09-01T00:00:00Z") }
        val other = seedPair(source, owner, new, third)
        val membership = purposeColumns(source, purpose).getValue("membership")!!.toInt()
        val result = decide(source, new, request(pair, DuplicateDecision.DELETE_NEW))
        assertEquals(new, result.deletedItemId)
        assertEquals(listOf(existing), result.remainingItemIds)
        assertEquals(listOf(DuplicatePurposeCount(purpose, 1, membership + 1)), result.purposes)
        assertEquals(mapOf("lifecycle_status" to "DELETED", "version" to "4"), lifecycle(source, new))
        assertEquals(mapOf("lifecycle_status" to "ACTIVE", "version" to "3"), lifecycle(source, existing))
        assertEquals("DECIDED DELETE_NEW 2", pairColumns(source, pair))
        assertEquals("OPEN - 1", pairColumns(source, other), "other pairs keep their row but are no longer visible")
        assertEquals("WISHLIST_ITEM_NOT_FOUND", assertFailsWith<DuplicateException> { DuplicateReadService(source).list(owner, new, 20, null) }.code)
    }

    @Test fun `delete existing releases its media and keeps the new item and its review`() = withAnalysisDatabase { source ->
        val (media, _) = insertMedia(source, owner)
        val (new, existing, pair) = pairOf(source, existingSeed = ItemSeed(media = media, imageSource = "USER"))
        val newBefore = itemColumns(source, new, "version", "review_status", "category_id", "purpose_id", "updated_at")
        val result = decide(source, new, request(pair, DuplicateDecision.DELETE_EXISTING))
        assertEquals(existing, result.deletedItemId)
        assertEquals(listOf(new), result.remainingItemIds)
        assertEquals(mapOf("lifecycle_status" to "DELETED", "version" to "4"), lifecycle(source, existing))
        assertNull(itemColumns(source, existing, "media_asset_id").getValue("media_asset_id"))
        assertNotNull(mediaColumns(source, media).getValue("unreferenced"))
        assertEquals(newBefore, itemColumns(source, new, "version", "review_status", "category_id", "purpose_id", "updated_at"))
        assertEquals("PENDING", newBefore.getValue("review_status"))
    }

    @Test fun `a failure after the writes rolls back the decision the deletion and the receipt`() = withAnalysisDatabase { source ->
        val (new, existing, pair) = pairOf(source)
        val service = DuplicateDecisionService(source) { error("injected") }
        assertFailsWith<IllegalStateException> { service.decide(owner, new, UUID.randomUUID(), request(pair, DuplicateDecision.DELETE_EXISTING)) }
        assertEquals(mapOf("lifecycle_status" to "ACTIVE", "version" to "3"), lifecycle(source, existing))
        assertEquals("OPEN - 1", pairColumns(source, pair))
        assertEquals("0", analysisScalar(source, "select count(*) from duplicate_decision_receipts"))
    }

    @Test fun `review decisions and duplicate decisions stay independent`() = withAnalysisDatabase { source ->
        val (new, _, pair) = pairOf(source)
        val third = seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-09-01T00:00:00Z") }
        val other = seedPair(source, owner, new, third)
        decide(source, new, request(pair, DuplicateDecision.KEEP_BOTH))
        assertEquals("PENDING", itemColumns(source, new, "review_status").getValue("review_status"))
        ItemMutationService(source).decideReview(owner, new, 3, ReviewDecision.CONFIRM, ItemChanges())
        assertEquals("CONFIRMED", itemColumns(source, new, "review_status").getValue("review_status"))
        assertEquals("DECIDED KEEP_BOTH 2", pairColumns(source, pair))
        assertEquals("OPEN - 1", pairColumns(source, other))
        // A confirmed item can still resolve its remaining pair.
        val result = decide(source, new, request(other, DuplicateDecision.DELETE_EXISTING))
        assertEquals(third, result.deletedItemId)
        assertEquals("CONFIRMED", itemColumns(source, new, "review_status").getValue("review_status"))
    }

    @Test fun `several candidates are decided pair by pair`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner)
        val (a, b, c) = List(3) { i -> seedAnalyzedItem(source, owner).also { setCreatedAt(source, it, "2026-10-0${i + 1}T00:00:00Z") } }
        val pairs = listOf(a, b, c).map { seedPair(source, owner, new, it) }
        decide(source, new, request(pairs[0], DuplicateDecision.DELETE_EXISTING))
        decide(source, new, request(pairs[1], DuplicateDecision.KEEP_BOTH))
        val page = DuplicateReadService(source).list(owner, new, 20, null)
        assertEquals(listOf(c), page.candidates.map { it.existingItem.id })
        assertEquals(listOf("DECIDED DELETE_EXISTING 2", "DECIDED KEEP_BOTH 2", "OPEN - 1"), pairs.map { pairColumns(source, it) })
        assertEquals("DELETED", lifecycle(source, a).getValue("lifecycle_status"))
        assertEquals("ACTIVE", lifecycle(source, b).getValue("lifecycle_status"))
    }

    @Test fun `errors follow the documented check order`() = withAnalysisDatabase { source ->
        val (new, existing, pair) = pairOf(source)
        val foreignOwner = UUID.randomUUID()
        assertEquals("WISHLIST_ITEM_NOT_FOUND", assertFailsWith<DuplicateException> {
            DuplicateDecisionService(source).decide(foreignOwner, new, UUID.randomUUID(), request(pair, DuplicateDecision.KEEP_BOTH))
        }.code)
        // A pair of another new item, and an unknown pair, are not this item's candidates.
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", conflict(source, existing, request(pair, DuplicateDecision.KEEP_BOTH)).code)
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", conflict(source, new, request(UUID.randomUUID(), DuplicateDecision.KEEP_BOTH)).code)
        conflict(source, new, request(pair, DuplicateDecision.KEEP_BOTH, candidateVersion = 2)).let {
            assertEquals("DUPLICATE_CANDIDATE_VERSION_CONFLICT", it.code); assertEquals(1, it.currentVersion)
        }
        conflict(source, new, request(pair, DuplicateDecision.DELETE_EXISTING, itemVersion = 2)).let {
            assertEquals("WISHLIST_ITEM_VERSION_CONFLICT", it.code); assertEquals(existing, it.itemId); assertEquals(3, it.currentVersion)
        }
        conflict(source, new, request(pair, DuplicateDecision.DELETE_NEW, itemVersion = 9)).let {
            assertEquals("WISHLIST_ITEM_VERSION_CONFLICT", it.code); assertEquals(new, it.itemId); assertEquals(3, it.currentVersion)
        }
        assertEquals("OPEN - 1", pairColumns(source, pair), "rejections change nothing")
        decide(source, new, request(pair, DuplicateDecision.KEEP_BOTH))
        conflict(source, new, request(pair, DuplicateDecision.DELETE_EXISTING, candidateVersion = 2)).let {
            assertEquals("DUPLICATE_DECISION_NOT_ALLOWED", it.code); assertEquals(2, it.currentVersion)
        }
        // A withdrawn pair is gone for the user.
        val (new2, _, withdrawn) = pairOf(source)
        analysisSql(source, "update duplicate_candidates set status='WITHDRAWN', version=2 where id='$withdrawn'")
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", conflict(source, new2, request(withdrawn, DuplicateDecision.KEEP_BOTH, 2)).code)
    }

    @Test fun `a pair waiting for the existing item's reanalysis cannot be decided`() = withAnalysisDatabase { source ->
        val (new, existing, pair) = pairOf(source, existingSeed = ItemSeed(analysis = "FAILED_RETRYABLE", review = "NOT_REQUIRED"))
        assertEquals(DuplicateKind.SAME_URL, DuplicateReadService(source).list(owner, new, 20, null).candidates.single().kind)
        ReanalysisService(source).reanalyze(owner, existing, UUID.randomUUID())
        val version = itemColumns(source, existing, "version").getValue("version")!!.toInt()
        conflict(source, new, request(pair, DuplicateDecision.DELETE_EXISTING, itemVersion = version)).let {
            assertEquals("DUPLICATE_DECISION_NOT_ALLOWED", it.code); assertEquals(1, it.currentVersion)
        }
        assertEquals("ACTIVE", lifecycle(source, existing).getValue("lifecycle_status"))
        // The new item turning back to PROCESSING hides the pair: it exists but has no kind to decide.
        analysisSql(source, "update wishlist_items set analysis_status='PROCESSING' where id='$new'")
        assertEquals("DUPLICATE_DECISION_NOT_ALLOWED", conflict(source, new, request(pair, DuplicateDecision.KEEP_BOTH)).code)
    }

    @Test fun `a key replays its stored result and rejects another payload`() = withAnalysisDatabase { source ->
        val (new, existing, pair) = pairOf(source)
        val key = UUID.randomUUID()
        val first = decide(source, new, request(pair, DuplicateDecision.DELETE_NEW), key)
        val replay = decide(source, new, request(pair, DuplicateDecision.DELETE_NEW), key)
        assertTrue(replay.replayed)
        assertEquals(first.copy(replayed = true), replay)
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict(source, new, request(pair, DuplicateDecision.KEEP_BOTH), key).code)
        assertEquals("WISHLIST_ITEM_NOT_FOUND", conflict(source, new, request(pair, DuplicateDecision.DELETE_NEW)).code, "a new key is a new request")
        assertEquals("1", analysisScalar(source, "select count(*) from duplicate_decision_receipts"))
        assertEquals("ACTIVE", lifecycle(source, existing).getValue("lifecycle_status"))

        val (new2, _, pair2) = pairOf(source)
        decide(source, new2, request(pair2, DuplicateDecision.KEEP_BOTH))
        assertEquals("DUPLICATE_CANDIDATE_VERSION_CONFLICT", conflict(source, new2, request(pair2, DuplicateDecision.KEEP_BOTH)).code)
        assertEquals("DUPLICATE_DECISION_NOT_ALLOWED", conflict(source, new2, request(pair2, DuplicateDecision.KEEP_BOTH, candidateVersion = 2)).code)
    }

    @Test fun `the kotlin kind table matches the sql one`() = withAnalysisDatabase { source ->
        val statuses = listOf("PROCESSING", "READY", "PARTIAL", "FAILED_RETRYABLE", "FAILED_TERMINAL")
        val subsets = (1 until (1 shl DuplicateBasis.entries.size)).map { mask -> DuplicateBasis.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }
        source.connection.use { c ->
            val canonicals = listOf("https://shop.example/p", null)
            for (n in statuses) for (canonical in canonicals) for (e in statuses) for (existingCanonical in canonicals) for (bases in subsets) {
                val sql = "select ${DuplicateKinds.kindSql("n", "e", "d")} from (select ?::text analysis_status, ?::text canonical_url) n, " +
                    "(select ?::text analysis_status, ?::text canonical_url) e, (select string_to_array(?, ',') bases) d"
                val fromSql = c.prepareStatement(sql).use { s ->
                    s.setString(1, n); s.setString(2, canonical); s.setString(3, e); s.setString(4, existingCanonical)
                    s.setString(5, bases.joinToString(",") { it.name })
                    s.executeQuery().use { r -> r.next(); r.getString(1) }
                }
                assertEquals(DuplicateKinds.kindOf(n, canonical, e, existingCanonical, bases.toSet())?.name, fromSql,
                    "$n $canonical $e $existingCanonical $bases")
            }
        }
    }
}
