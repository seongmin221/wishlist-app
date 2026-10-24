package app.candidates

import app.analysis.AnalysisClaimRepository
import app.analysis.AnalysisJobReconciler
import app.analysis.AnalysisLane
import app.analysis.ClaimResult
import app.analysis.WorkerDisposition
import app.common.FieldChange
import app.testutil.*
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationException
import app.wishlist.ItemMutationService
import app.wishlist.ReanalysisResult
import app.wishlist.ReanalysisService
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class StructureDeletionConcurrencyTest {
    private val kind = DeletionKind.CATEGORY

    private fun <A, B> race(left: () -> A, right: () -> B): Pair<Result<A>, Result<B>> {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(left) })
            val b = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(right) })
            return a.get(30, TimeUnit.SECONDS) to b.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    private fun Result<*>.code(): String = when (val e = exceptionOrNull()) {
        null -> "OK"
        is CandidateException -> e.code
        is ItemMutationException -> e.code
        else -> throw e
    }

    private fun assertDeletedReference(source: DataSource, item: UUID, label: String) {
        val now = classification(source, item)
        assertNull(now["custom_category_id"], label); assertNull(now["category_id"], label); assertNull(now["category_source"], label)
        assertEquals("CUSTOM_CATEGORY_DELETED", now["category_missing_reason"], label)
        assertTrue("CATEGORY" in now["user_override_fields"]!!, label)
    }

    private fun categoryItem(source: DataSource, owner: UUID, lane: AnalysisLane, category: UUID, categorySource: String = "AI") =
        processingItem(source, owner, lane, "custom_category_id='$category',category_source='$categorySource',category_missing_reason=null")

    @Test fun `running result cannot reattach deleted category`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        for (lane in AnalysisLane.entries) {
            val category = insertCustomCategory(source, owner)
            val job = categoryItem(source, owner, lane, category)
            val claim = claimJob(source, job.jobId, lane)
            savePending(source, claim, category.toString())
            source.deleteWithImpact(owner, kind, category)
            assertEquals("PROCESSING", classification(source, job.itemId)["analysis_status"], "$lane delete keeps analysis")
            assertEquals("${lane.name}_RUNNING", jobStage(source, job.jobId))
            val outbox = outboxCount(source, job.itemId)

            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claim))
            assertEquals("CANCELLED", jobStage(source, job.jobId), "$lane stale finish cancels")
            assertEquals("FAILED_RETRYABLE", classification(source, job.itemId)["analysis_status"])
            assertDeletedReference(source, job.itemId, "$lane stale finish")
            assertNull(itemColumns(source, job.itemId, "product_name")["product_name"], "$lane pending result is not applied")
            assertEquals(outbox, outboxCount(source, job.itemId))

            // Reverse order: the Worker finishes first, so the earlier token is stale and a fresh impact deletes.
            val kept = insertCustomCategory(source, owner)
            val first = categoryItem(source, owner, lane, kept, categorySource = "USER")
            val oldImpact = source.impact(owner, kind, kept)
            val running = claimJob(source, first.jobId, lane)
            savePending(source, running, "C026")
            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, running))
            assertEquals("READY", classification(source, first.itemId)["analysis_status"], "$lane user category survives the result")
            assertEquals(kept.toString(), classification(source, first.itemId)["custom_category_id"])
            val error = assertFailsWith<CandidateException> { source.deleteStructure(owner, kind, kept, oldImpact.version, oldImpact.impactToken) }
            assertEquals("CATEGORY_DELETION_IMPACT_CONFLICT", error.code)
            source.deleteWithImpact(owner, kind, kept)
            assertDeletedReference(source, first.itemId, "$lane finish then delete")
            assertEquals("READY", classification(source, first.itemId)["analysis_status"], "analysis stays as the Worker left it")
        }
    }

    @Test fun `expired stale category lease cancels without retry outbox`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        for (lane in AnalysisLane.entries) {
            val category = insertCustomCategory(source, owner)
            val job = categoryItem(source, owner, lane, category)
            val claim = claimJob(source, job.jobId, lane)
            savePending(source, claim, category.toString())
            source.deleteWithImpact(owner, kind, category)
            val outbox = outboxCount(source, job.itemId)
            expireLease(source, job.jobId)

            assertEquals(1, AnalysisJobReconciler(source).reconcileExpired(), "$lane lease recovery")
            assertEquals("CANCELLED", jobStage(source, job.jobId))
            assertEquals("FAILED_RETRYABLE", classification(source, job.itemId)["analysis_status"])
            assertEquals(outbox, outboxCount(source, job.itemId), "$lane stale job gets no retry outbox")
            assertDeletedReference(source, job.itemId, "$lane recovered")
            val recovered = finishSnapshot(source, FinishJob(job.itemId, job.jobId))
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired(), "$lane second recovery is a no-op")
            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claim), "$lane late finish")
            assertEquals(recovered, finishSnapshot(source, FinishJob(job.itemId, job.jobId)), "$lane nothing changes after recovery")

            // An explicit reanalysis runs a new generation; the deleted reference still does not come back.
            val started = ReanalysisService(source).reanalyze(owner, job.itemId, UUID.randomUUID())
            assertIs<ReanalysisResult.Started>(started)
            val next = UUID.fromString(analysisScalar(source,
                "select id from analysis_jobs where wishlist_item_id='${job.itemId}' and generation=2"))
            if (lane == AnalysisLane.BROWSER) analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true where id='$next'")
            val nextClaim = (AnalysisClaimRepository(source).claim(next, 2, lane) as ClaimResult.Claimed).claim
            savePending(source, nextClaim, category.toString())
            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, nextClaim))
            assertDeletedReference(source, job.itemId, "$lane new generation")
            assertEquals("PARTIAL", classification(source, job.itemId)["analysis_status"])

            val reassigned = ItemMutationService(source).edit(owner, job.itemId,
                classification(source, job.itemId)["version"]!!.toInt(), ItemChanges(category = FieldChange.Set(app.category.CategoryRef.Public("C026"))))
            assertEquals("C026", reassigned.storedState.state.categoryId, "$lane the user reassigns with PATCH")
        }
    }

    @Test fun `concurrent deletions change once and a racing edit loses one side`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(3) { round ->
            val category = insertCustomCategory(source, owner)
            val item = seedItem(source, owner, ItemSeed(customCategory = category))
            val impact = source.impact(owner, kind, category)
            val (a, b) = race({ source.deleteStructure(owner, kind, category, 1, impact.impactToken) },
                { source.deleteStructure(owner, kind, category, 1, impact.impactToken) })
            assertEquals(listOf("OK", "OK"), listOf(a.code(), b.code()), "round $round: replay of the winner is 204")
            assertEquals("4", classification(source, item)["version"])
            assertEquals("1", analysisScalar(source, "select count(*) from structure_deletion_receipts where resource_id='$category'"))

            val other = insertCustomCategory(source, owner)
            val edited = seedItem(source, owner, ItemSeed(customCategory = other))
            val otherImpact = source.impact(owner, kind, other)
            val (delete, edit) = race({ source.deleteStructure(owner, kind, other, 1, otherImpact.impactToken) },
                { ItemMutationService(source).edit(owner, edited, 3, ItemChanges(name = FieldChange.Set("경합 $round"))) })
            val codes = listOf(delete.code(), edit.code())
            assertEquals(1, codes.count { it == "OK" }, "round $round: $codes")
            assertTrue(codes.single { it != "OK" } in setOf("CATEGORY_DELETION_IMPACT_CONFLICT", "WISHLIST_ITEM_VERSION_CONFLICT"))
            assertEquals("4", classification(source, edited)["version"])
        }
    }
}
