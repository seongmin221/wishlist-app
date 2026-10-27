package app.candidates

import app.analysis.AnalysisClaimRepository
import app.analysis.AnalysisJobReconciler
import app.analysis.AnalysisLane
import app.analysis.ClaimResult
import app.analysis.WorkerDisposition
import app.common.FieldChange
import app.purpose.PurposeProjection
import app.purpose.PurposeService
import app.testutil.*
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationException
import app.wishlist.ItemMutationService
import app.wishlist.ReadPosition
import app.wishlist.ReanalysisResult
import app.wishlist.ReanalysisService
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class PurposeDeletionTest {
    private val kind = DeletionKind.PURPOSE

    private fun expectError(code: String, block: () -> Unit) =
        assertFailsWith<CandidateException> { block() }.also { assertEquals(code, it.code); assertNull(it.itemId); assertNull(it.currentVersion) }

    private fun assertUserUnassigned(source: DataSource, item: UUID, label: String) =
        assertEquals(mapOf("purpose_id" to null, "purpose_source" to "USER"), itemColumns(source, item, "purpose_id", "purpose_source"), label)

    private fun purposeItem(source: DataSource, owner: UUID, lane: AnalysisLane, purpose: UUID) =
        processingItem(source, owner, lane, "purpose_id='$purpose',purpose_source='AI'")

    @Test fun `delete keeps items as user unassigned and preserves category review and analysis`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "삭제할 목적", null)
        val aiItem = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val userItem = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", categorySource = "USER", review = "CONFIRMED", version = 6))
        val unnamed = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", name = null, nameSource = null, analysis = "PARTIAL",
            review = "NOT_REQUIRED", category = null, categorySource = null, missingReason = "AI_ABSTAINED"))
        val processing = purposeItem(source, owner, AnalysisLane.GENERAL, purpose)
        val archived = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", lifecycle = "ARCHIVED"))
        val affected = listOf(aiItem, userItem, unnamed, processing.itemId)
        val before = affected.associateWith { classification(source, it) }
        val archivedBefore = itemSnapshot(source, listOf(archived))
        val purposeBefore = purposeState(source, purpose)

        val impact = source.impact(owner, kind, purpose)
        assertEquals(4, impact.affectedCount); assertEquals(affected.toSet(), impact.items.map { it.id }.toSet())
        assertEquals(1, impact.version)
        expectError("PURPOSE_NOT_FOUND") { source.impact(UUID.randomUUID(), kind, purpose) }
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, purpose, 2, impact.impactToken) }
        assertEquals(before, affected.associateWith { classification(source, it) })

        source.deleteStructure(owner, kind, purpose, 1, impact.impactToken)

        for ((id, old) in before) {
            assertUserUnassigned(source, id, "$id")
            val now = classification(source, id)
            assertEquals(old["version"]!!.toInt() + 1, now["version"]!!.toInt())
            for (kept in listOf("category_id", "category_source", "category_missing_reason", "review_status", "analysis_status", "user_override_fields")) {
                assertEquals(old[kept], now[kept], "$kept of $id")
            }
        }
        assertEquals(archivedBefore, itemSnapshot(source, listOf(archived)))
        assertEquals("GENERAL_PENDING", jobStage(source, processing.jobId))
        val after = purposeState(source, purpose)
        assertEquals("DELETED", after["lifecycle"]); assertEquals("2", after["version"])
        assertEquals(purposeBefore["membership"]!!.toInt() + 4, after["membership"]!!.toInt(), "one membership step per removed item")
        assertEquals(purposeBefore["activity"], after["activity"]); assertEquals(purposeBefore["kind"], after["kind"])
        assertNull(PurposeService(source).get(owner, purpose))
        assertTrue(PurposeService(source).list(owner, PurposeProjection.SELECT, 20, null).entries.none { it.purpose.id == purpose })
        assertEquals(1, deletionReceipts(source))

        val snapshot = itemSnapshot(source, affected)
        source.deleteStructure(owner, kind, purpose, 1, impact.impactToken)
        assertEquals(snapshot, itemSnapshot(source, affected)); assertEquals(after, purposeState(source, purpose)); assertEquals(1, deletionReceipts(source))
        expectError("PURPOSE_NOT_FOUND") { source.deleteStructure(owner, kind, purpose, 1, "0".repeat(64)) }
        expectError("PURPOSE_NOT_FOUND") { source.deleteStructure(owner, kind, purpose, 2, impact.impactToken) }
        expectError("PURPOSE_NOT_FOUND") { source.deleteStructure(UUID.randomUUID(), kind, purpose, 1, impact.impactToken) }
        expectError("PURPOSE_NOT_FOUND") { source.impact(owner, kind, purpose) }
        expectError("PURPOSE_NOT_FOUND") { CandidateMoveService(source).move(owner, purpose, UUID.randomUUID(), CandidateMoveRequest(1, listOf(MoveSelection(aiItem, 4, null)))) }
        val relink = assertFailsWith<ItemMutationException> { ItemMutationService(source).edit(owner, aiItem, 4, ItemChanges(purpose = FieldChange.Set(purpose))) }
        assertEquals("PURPOSE_NOT_AVAILABLE", relink.code)

        val empty = insertPurpose(source, owner, "빈 목적", null)
        val emptyImpact = source.impact(owner, kind, empty)
        assertEquals(0, emptyImpact.affectedCount); assertEquals(emptyList(), emptyImpact.items)
        source.deleteStructure(owner, kind, empty, 1, emptyImpact.impactToken)
        assertEquals("DELETED", purposeState(source, empty)["lifecycle"])
        val archivedPurpose = insertPurpose(source, owner, "보관 목적", null).also { archivePurpose(source, it) }
        expectError("PURPOSE_NOT_FOUND") { source.impact(owner, kind, archivedPurpose) }
    }

    @Test fun `impact token follows membership changes outside the page`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "목적", null)
        val items = List(3) { seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", review = "CONFIRMED")) }
        val page = source.impact(owner, kind, purpose, limit = 1)
        val newcomer = seedItem(source, owner)
        CandidateMoveService(source).move(owner, purpose, UUID.randomUUID(), CandidateMoveRequest(1, listOf(MoveSelection(newcomer, 3, null))))
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, purpose, 1, page.impactToken) }
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.impact(owner, kind, purpose, 1, page.next, page.impactToken) }
        val fresh = source.impact(owner, kind, purpose)
        ItemMutationService(source).edit(owner, items.last(), 3, ItemChanges(purpose = FieldChange.Set(null)))
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, purpose, 1, fresh.impactToken) }
        val metadata = source.impact(owner, kind, purpose)
        PurposeService(source).patch(owner, purpose, 1, app.purpose.PurposeChanges(name = "새 이름"))
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, purpose, 1, metadata.impactToken) }
        source.deleteWithImpact(owner, kind, purpose)
        assertEquals("DELETED", purposeState(source, purpose)["lifecycle"])
    }

    @Test fun `impact pages with tied createdAt have exclusive boundaries`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "목적", null)
        val tied = List(5) { seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER")).also { setCreatedAt(source, it, "2026-10-24T01:00:00Z") } }
        val older = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER")).also { setCreatedAt(source, it, "2026-10-23T01:00:00Z") }
        val expected = tied.sortedWith(sqlUuidOrder.reversed()) + older
        val token = source.impact(owner, kind, purpose).impactToken
        for (limits in listOf(listOf(2), listOf(1, 3, 100), listOf(100))) {
            val seen = mutableListOf<UUID>()
            var after: ReadPosition? = null
            var step = 0
            do {
                val page = source.impact(owner, kind, purpose, limits[minOf(step++, limits.lastIndex)], after, token.takeIf { after != null })
                assertEquals(6, page.affectedCount)
                seen += page.items.map { it.id }
                after = page.next
            } while (after != null)
            assertEquals(expected, seen, "limits $limits")
        }
        val first = source.impact(owner, kind, purpose, limit = 2)
        ItemMutationService(source).edit(owner, older, 3, ItemChanges(brand = FieldChange.Set(null)))
        expectError("PURPOSE_DELETION_IMPACT_CONFLICT") { source.impact(owner, kind, purpose, 2, first.next, first.impactToken) }
    }

    @Test fun `pending job preserves user unassignment after purpose deletion`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        for (lane in AnalysisLane.entries) {
            val purpose = insertPurpose(source, owner, "삭제 $lane", null)
            val replacement = insertPurpose(source, owner, "대체 $lane", null)
            val same = purposeItem(source, owner, lane, purpose)
            val other = purposeItem(source, owner, lane, purpose)
            val replacementBefore = purposeState(source, replacement)
            source.deleteWithImpact(owner, kind, purpose)
            for ((job, judged) in listOf(same to purpose, other to replacement)) {
                assertEquals("PROCESSING", classification(source, job.itemId)["analysis_status"])
                val claim = claimJob(source, job.jobId, lane)
                assertEquals(2, claim.expectedItemVersion, "$lane claims the deletion version")
                seedV3Snapshot(source, claim, listOf(purpose, replacement))
                savePending(source, claim, "C026", judged.toString(), judged = true)
                assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claim))
                assertUserUnassigned(source, job.itemId, "$lane judged $judged")
                val now = classification(source, job.itemId)
                assertEquals("READY", now["analysis_status"], "$lane the analysis itself succeeds")
                assertEquals("C026", now["category_id"]); assertEquals("AI", now["category_source"], "category is judged independently")
                assertEquals("PENDING", now["review_status"], "the AI category still waits for review")
            }
            assertEquals(replacementBefore, purposeState(source, replacement), "$lane replacement purpose gains nothing")
        }
    }

    @Test fun `running finish and expired lease preserve deleted purpose`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        for (lane in AnalysisLane.entries) {
            val purpose = insertPurpose(source, owner, "삭제 $lane", null)
            val replacement = insertPurpose(source, owner, "대체 $lane", null)
            val finished = purposeItem(source, owner, lane, purpose)
            val abandoned = purposeItem(source, owner, lane, purpose)
            val claims = listOf(finished, abandoned).map { job ->
                claimJob(source, job.jobId, lane).also { seedV3Snapshot(source, it, listOf(purpose, replacement)); savePending(source, it, "C026", purpose.toString(), true) }
            }
            source.deleteWithImpact(owner, kind, purpose)
            for (job in listOf(finished, abandoned)) assertEquals("${lane.name}_RUNNING", jobStage(source, job.jobId), "$lane delete keeps the running job")

            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claims[0]))
            assertEquals("CANCELLED", jobStage(source, finished.jobId)); assertEquals("FAILED_RETRYABLE", classification(source, finished.itemId)["analysis_status"])
            assertUserUnassigned(source, finished.itemId, "$lane stale finish")

            val outbox = outboxCount(source, abandoned.itemId)
            expireLease(source, abandoned.jobId)
            assertEquals(1, AnalysisJobReconciler(source).reconcileExpired(), "$lane lease recovery")
            assertEquals("CANCELLED", jobStage(source, abandoned.jobId)); assertEquals("FAILED_RETRYABLE", classification(source, abandoned.itemId)["analysis_status"])
            assertEquals(outbox, outboxCount(source, abandoned.itemId), "$lane no retry outbox for the stale job")
            assertUserUnassigned(source, abandoned.itemId, "$lane recovered")
            val recovered = finishSnapshot(source, FinishJob(abandoned.itemId, abandoned.jobId))
            assertEquals(0, AnalysisJobReconciler(source).reconcileExpired())
            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claims[1]))
            assertEquals(recovered, finishSnapshot(source, FinishJob(abandoned.itemId, abandoned.jobId)), "$lane late finish changes nothing")

            for (job in listOf(finished, abandoned)) {
                assertIs<ReanalysisResult.Started>(ReanalysisService(source).reanalyze(owner, job.itemId, UUID.randomUUID()))
                val next = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='${job.itemId}' and generation=2"))
                val claim = (AnalysisClaimRepository(source).claim(next, 2, AnalysisLane.GENERAL) as ClaimResult.Claimed).claim
                seedV3Snapshot(source, claim, listOf(replacement))
                savePending(source, claim, "C026", replacement.toString(), judged = true)
                finish(source, claim)
                assertUserUnassigned(source, job.itemId, "$lane new generation keeps the user unassignment")
                assertEquals("READY", classification(source, job.itemId)["analysis_status"])
            }
            assertEquals("0", purposeState(source, replacement)["count"])
        }
    }
}
