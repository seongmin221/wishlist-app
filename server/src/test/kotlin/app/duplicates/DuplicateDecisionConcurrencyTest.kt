package app.duplicates

import app.testutil.AnalysisPipeline
import app.testutil.analysisScalar
import app.testutil.identifiedPage
import app.testutil.itemColumns
import app.testutil.pairColumns
import app.testutil.seedAnalyzedItem
import app.testutil.seedPair
import app.testutil.setCreatedAt
import app.testutil.withAnalysisDatabase
import app.wishlist.DeleteResult
import app.wishlist.ItemMutationService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** B9d races on real PostgreSQL. Every writer takes the owner lock first, so these must serialize without deadlock. */
class DuplicateDecisionConcurrencyTest {
    private val owner = UUID.randomUUID()
    private val canonical = "https://shop.example/p"

    private fun existingAt(source: DataSource, day: Int) =
        seedAnalyzedItem(source, owner, canonical = canonical).also { setCreatedAt(source, it, "2026-10-%02dT00:00:00Z".format(day)) }

    private fun deleteExisting(pair: UUID) = DuplicateDecisionRequest(pair, DuplicateDecision.DELETE_EXISTING, 1, 3)
    private val keepBoth = { pair: UUID -> DuplicateDecisionRequest(pair, DuplicateDecision.KEEP_BOTH, 1, null) }

    /** Runs both actions at once after a shared start signal; each result is the value or the thrown exception. */
    private fun race(first: () -> Any, second: () -> Any): List<Any> {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(first, second).map { action ->
                pool.submit<Any> { start.await(); runCatching(action).fold({ it }, { it }) }
            }
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    /** Holds [service]'s transaction open after its writes until [during] has started and been observed waiting. */
    private fun <T> whileHolding(source: DataSource, hold: (DuplicateDecisionService) -> Any, during: () -> T): Pair<Any, T> {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = DuplicateDecisionService(source) { inside.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = pool.submit<Any> { runCatching { hold(service) }.fold({ it }, { it }) }
            check(inside.await(20, TimeUnit.SECONDS))
            val waiting: Future<T> = pool.submit<T> { during() }
            assertFailsWith<TimeoutException>("the other writer waits on the owner lock") { waiting.get(300, TimeUnit.MILLISECONDS) }
            release.countDown()
            return held.get(20, TimeUnit.SECONDS) to waiting.get(20, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `two keys for one pair delete once`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner, canonical = canonical)
        val existing = existingAt(source, 1)
        val pair = seedPair(source, owner, new, existing)
        val results = race({ DuplicateDecisionService(source).decide(owner, new, UUID.randomUUID(), deleteExisting(pair)) },
            { DuplicateDecisionService(source).decide(owner, new, UUID.randomUUID(), deleteExisting(pair)) })
        assertEquals(1, results.count { it is DuplicateDecisionResult })
        val loser = results.filterIsInstance<DuplicateException>().single()
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", loser.code, "the existing item is gone, so is the pair")
        assertEquals(mapOf("lifecycle_status" to "DELETED", "version" to "4"), itemColumns(source, existing, "lifecycle_status", "version"))
        assertEquals("1", analysisScalar(source, "select count(*) from duplicate_decision_receipts"))
    }

    @Test fun `the same key sent twice at once decides once and replays once`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner, canonical = canonical)
        val pair = seedPair(source, owner, new, existingAt(source, 1))
        val key = UUID.randomUUID()
        val results = race({ DuplicateDecisionService(source).decide(owner, new, key, keepBoth(pair)) },
            { DuplicateDecisionService(source).decide(owner, new, key, keepBoth(pair)) })
        val decided = results.map { it as DuplicateDecisionResult }
        assertEquals(listOf(false, true), decided.map { it.replayed }.sorted())
        assertEquals(decided[0].copy(replayed = false), decided[1].copy(replayed = false))
        assertEquals("DECIDED KEEP_BOTH 2", pairColumns(source, pair))
        assertEquals("1", analysisScalar(source, "select count(*) from duplicate_decision_receipts"))
    }

    @Test fun `ITEM-05 on the existing item waits for or precedes the decision`() = withAnalysisDatabase { source ->
        val new = seedAnalyzedItem(source, owner, canonical = canonical)
        val existing = existingAt(source, 1)
        val pair = seedPair(source, owner, new, existing)
        val (decided, deleted) = whileHolding(source, { it.decide(owner, new, UUID.randomUUID(), deleteExisting(pair)) }) {
            ItemMutationService(source).delete(owner, existing)
        }
        assertTrue(decided is DuplicateDecisionResult, decided.toString())
        assertEquals(DeleteResult.ALREADY_DELETED, deleted)

        val new2 = seedAnalyzedItem(source, owner, canonical = canonical)
        val existing2 = existingAt(source, 2)
        val pair2 = seedPair(source, owner, new2, existing2)
        ItemMutationService(source).delete(owner, existing2)
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", assertFailsWith<DuplicateException> {
            DuplicateDecisionService(source).decide(owner, new2, UUID.randomUUID(), deleteExisting(pair2))
        }.code)
        assertEquals("OPEN - 1", pairColumns(source, pair2))
    }

    @Test fun `an analysis finish of a third item serializes with the decision`() = withAnalysisDatabase { source ->
        val pipeline = AnalysisPipeline(source).apply { pages[canonical] = identifiedPage("Speedgoat 6") }
        val new = seedAnalyzedItem(source, owner, canonical = canonical)
        val existing = existingAt(source, 1)
        val pair = seedPair(source, owner, new, existing)
        val third = pipeline.save(owner, canonical)
        // The finish waits for the decision, then sees the existing item already deleted.
        val (decided, _) = whileHolding(source, { it.decide(owner, new, UUID.randomUUID(), deleteExisting(pair)) }) { pipeline.run(third) }
        assertTrue(decided is DuplicateDecisionResult, decided.toString())
        assertEquals("READY", itemColumns(source, third, "analysis_status").getValue("analysis_status"))
        assertEquals("0", analysisScalar(source, "select count(*) from duplicate_candidates where existing_item_id='$existing' and new_item_id='$third'"))
        assertEquals("1", analysisScalar(source, "select count(*) from duplicate_candidates where new_item_id='$third' and existing_item_id='$new'"))

        // The other order: a finish first records a pair with the existing item, then the decision deletes it.
        val new2 = seedAnalyzedItem(source, owner, canonical = canonical)
        val existing2 = existingAt(source, 2)
        val pair2 = seedPair(source, owner, new2, existing2)
        val fourth = pipeline.saveAndRun(owner, canonical)
        assertEquals("1", analysisScalar(source, "select count(*) from duplicate_candidates where new_item_id='$fourth' and existing_item_id='$existing2'"))
        DuplicateDecisionService(source).decide(owner, new2, UUID.randomUUID(), deleteExisting(pair2))
        assertEquals(0, DuplicateReadService(source).list(owner, fourth, 50, null).candidates.count { it.existingItem.id == existing2 })
    }

    @Test fun `two pairs deleting the same existing item at once`() = withAnalysisDatabase { source ->
        val a = existingAt(source, 1)
        val b = existingAt(source, 2)
        val c = existingAt(source, 3)
        val ba = seedPair(source, owner, b, a)
        val ca = seedPair(source, owner, c, a)
        val results = race({ DuplicateDecisionService(source).decide(owner, b, UUID.randomUUID(), deleteExisting(ba)) },
            { DuplicateDecisionService(source).decide(owner, c, UUID.randomUUID(), deleteExisting(ca)) })
        assertEquals(1, results.count { it is DuplicateDecisionResult })
        assertEquals("DUPLICATE_CANDIDATE_NOT_FOUND", results.filterIsInstance<DuplicateException>().single().code)
        assertEquals("DELETED", itemColumns(source, a, "lifecycle_status").getValue("lifecycle_status"))
    }
}
