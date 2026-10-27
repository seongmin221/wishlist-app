package app.candidates

import app.analysis.AnalysisLane
import app.analysis.WorkerDisposition
import app.category.CategoryRef
import app.category.CategoryService
import app.common.FieldChange
import app.home.HomeReadService
import app.testutil.*
import app.wishlist.GetWishlistItemService
import app.wishlist.HomeActionGroup
import app.wishlist.ItemChanges
import app.wishlist.ItemMutationService
import app.wishlist.ReadPosition
import app.wishlist.RequiredAction
import app.wishlist.WishlistItemPolicy
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class CategoryDeletionTest {
    private val kind = DeletionKind.CATEGORY

    private fun expectError(code: String, block: () -> Unit) =
        assertFailsWith<CandidateException> { block() }.also { assertEquals(code, it.code); assertNull(it.itemId); assertNull(it.currentVersion) }

    private fun categoryVersion(source: DataSource, id: UUID) = analysisScalar(source, "select version from custom_categories where id='$id'")!!.toInt()

    @Test fun `impact counts every active reference and its token follows the whole impact`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val category = insertCustomCategory(source, owner)
        val named = seedItem(source, owner, ItemSeed(customCategory = category))
        val unnamed = seedItem(source, owner, ItemSeed(customCategory = category, name = null, nameSource = null, analysis = "PARTIAL",
            review = "NOT_REQUIRED"))
        val processing = processingItem(source, owner, AnalysisLane.GENERAL,
            "custom_category_id='$category',category_source='AI',category_missing_reason=null").itemId
        seedItem(source, owner, ItemSeed(customCategory = category, lifecycle = "ARCHIVED"))
        seedItem(source, owner, ItemSeed(customCategory = category, lifecycle = "DELETED"))
        val unrelated = seedItem(source, owner)

        val impact = source.impact(owner, kind, category)
        assertEquals(1, impact.version)
        assertEquals(3, impact.affectedCount)
        assertEquals(setOf(named, unnamed, processing), impact.items.map { it.id }.toSet())
        assertNull(impact.next)
        assertTrue(impact.impactToken.matches(Regex("[0-9a-f]{64}")))
        assertEquals(impact.impactToken, source.impact(owner, kind, category, limit = 1).impactToken, "token is the whole impact, not a page")

        ItemMutationService(source).edit(owner, unrelated, 3, ItemChanges(name = FieldChange.Set("다른 이름")))
        assertEquals(impact.impactToken, source.impact(owner, kind, category).impactToken, "unrelated edits keep the token")
        ItemMutationService(source).edit(owner, named, 3, ItemChanges(name = FieldChange.Set("새 이름")))
        val edited = source.impact(owner, kind, category)
        assertNotEquals(impact.impactToken, edited.impactToken, "an affected item edit changes the token")
        seedItem(source, owner, ItemSeed(customCategory = category))
        assertNotEquals(edited.impactToken, source.impact(owner, kind, category).impactToken, "a new link changes the token")

        val empty = insertCustomCategory(source, owner)
        val emptyImpact = source.impact(owner, kind, empty)
        assertEquals(0, emptyImpact.affectedCount); assertEquals(emptyList(), emptyImpact.items); assertNull(emptyImpact.next)
        assertNotEquals(emptyImpact.impactToken, source.impact(owner, kind, insertCustomCategory(source, owner)).impactToken, "IDs are in the token")

        expectError("CATEGORY_NOT_FOUND") { source.impact(UUID.randomUUID(), kind, category) }
        expectError("CATEGORY_NOT_FOUND") { source.impact(owner, kind, insertCustomCategory(source, owner, deleted = true)) }
        expectError("CATEGORY_NOT_FOUND") { source.impact(owner, kind, UUID.randomUUID()) }
    }

    @Test fun `impact pages with tied createdAt have exclusive boundaries`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val category = insertCustomCategory(source, owner)
        val tied = List(5) { seedItem(source, owner, ItemSeed(customCategory = category)).also { setCreatedAt(source, it, "2026-10-24T01:00:00Z") } }
        val newer = seedItem(source, owner, ItemSeed(customCategory = category)).also { setCreatedAt(source, it, "2026-10-24T02:00:00Z") }
        val expected = listOf(newer) + tied.sortedWith(sqlUuidOrder.reversed())
        val token = source.impact(owner, kind, category).impactToken

        for (limits in listOf(listOf(2), listOf(1, 3, 100), listOf(100))) {
            val seen = mutableListOf<UUID>()
            var after: ReadPosition? = null
            var step = 0
            do {
                val page = source.impact(owner, kind, category, limits[minOf(step++, limits.lastIndex)], after, token.takeIf { after != null })
                assertEquals(6, page.affectedCount); assertEquals(token, page.impactToken)
                seen += page.items.map { it.id }
                after = page.next
                if (after != null) assertEquals(page.items.last().let { ReadPosition(it.createdAt, it.id) }, after)
            } while (after != null)
            assertEquals(expected, seen, "limits $limits")
            assertEquals(6, seen.toSet().size)
        }

        val first = source.impact(owner, kind, category, limit = 2)
        ItemMutationService(source).edit(owner, expected.last(), 3, ItemChanges(name = FieldChange.Set("페이지 밖")))
        expectError("CATEGORY_DELETION_IMPACT_CONFLICT") { source.impact(owner, kind, category, 2, first.next, first.impactToken) }
    }

    @Test fun `delete clears active references with an override and keeps purpose review and analysis`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "목적", null)
        val category = insertCustomCategory(source, owner)
        val pending = seedItem(source, owner, ItemSeed(customCategory = category, purpose = purpose, purposeSource = "AI"))
        val confirmed = seedItem(source, owner, ItemSeed(customCategory = category, categorySource = "USER", review = "CONFIRMED", version = 5))
        val processing = processingItem(source, owner, AnalysisLane.GENERAL,
            "custom_category_id='$category',category_source='AI',category_missing_reason=null,predicted_category_id='$category'")
        val archived = seedItem(source, owner, ItemSeed(customCategory = category, lifecycle = "ARCHIVED"))
        val archivedBefore = itemSnapshot(source, listOf(archived))
        val before = listOf(pending, confirmed, processing.itemId).associateWith { classification(source, it) }
        val updatedBefore = listOf(pending, confirmed).associateWith { itemColumns(source, it, "updated_at")["updated_at"] }
        val purposeBefore = purposeState(source, purpose)
        val impact = source.impact(owner, kind, category)

        expectError("CATEGORY_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, category, 1, "0".repeat(64)) }
        expectError("CATEGORY_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, category, 2, impact.impactToken) }
        assertEquals(before, listOf(pending, confirmed, processing.itemId).associateWith { classification(source, it) })
        assertEquals(0, deletionReceipts(source))

        source.deleteStructure(owner, kind, category, 1, impact.impactToken)

        for ((id, old) in before) {
            val now = classification(source, id)
            assertNull(now["custom_category_id"]); assertNull(now["category_source"])
            assertEquals("CUSTOM_CATEGORY_DELETED", now["category_missing_reason"])
            assertTrue("CATEGORY" in now["user_override_fields"]!!, "override recorded on $id")
            assertEquals(old["version"]!!.toInt() + 1, now["version"]!!.toInt())
            for (kept in listOf("purpose_id", "purpose_source", "review_status", "analysis_status")) assertEquals(old[kept], now[kept], "$kept of $id")
        }
        updatedBefore.forEach { (id, at) -> assertNotEquals(at, itemColumns(source, id, "updated_at")["updated_at"]) }
        assertEquals(category.toString(), itemColumns(source, processing.itemId, "predicted_category_id")["predicted_category_id"], "diagnostics stay")
        assertEquals("GENERAL_PENDING", jobStage(source, processing.jobId), "analysis job is preserved")
        assertEquals(archivedBefore, itemSnapshot(source, listOf(archived)), "archived items are untouched")
        assertEquals(purposeBefore, purposeState(source, purpose), "category deletion changes no membership")
        assertEquals(2, categoryVersion(source, category))
        assertNotNull(analysisScalar(source, "select deleted_at from custom_categories where id='$category'"))
        assertNull(CategoryService(source).get(owner, category))
        assertEquals(1, deletionReceipts(source))

        val item = assertNotNull(GetWishlistItemService(source).get(owner, pending))
        assertEquals(RequiredAction.CATEGORY_REASSIGNMENT, WishlistItemPolicy.evaluate(item.storedState.state).requiredAction)
        assertTrue(pending in HomeReadService(source).get(owner).actionGroups.single { it.group == HomeActionGroup.INFORMATION_COMPLETION }.items.map { it.id })
        val reassigned = ItemMutationService(source).edit(owner, pending, 4, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C026"))))
        assertEquals("C026", reassigned.storedState.state.categoryId); assertNull(reassigned.storedState.state.categoryMissingReason)
        assertEquals(RequiredAction.CLASSIFICATION_REVIEW, WishlistItemPolicy.evaluate(reassigned.storedState.state).requiredAction,
            "the AI purpose still waits for review")

        val snapshot = itemSnapshot(source, before.keys)
        source.deleteStructure(owner, kind, category, 1, impact.impactToken)
        assertEquals(snapshot, itemSnapshot(source, before.keys), "replay changes nothing")
        assertEquals(2, categoryVersion(source, category)); assertEquals(1, deletionReceipts(source))
        expectError("CATEGORY_NOT_FOUND") { source.deleteStructure(owner, kind, category, 1, "0".repeat(64)) }
        expectError("CATEGORY_NOT_FOUND") { source.deleteStructure(owner, kind, category, 2, impact.impactToken) }
        expectError("CATEGORY_NOT_FOUND") { source.deleteStructure(UUID.randomUUID(), kind, category, 1, impact.impactToken) }
        expectError("CATEGORY_NOT_FOUND") { source.impact(owner, kind, category) }

        val empty = insertCustomCategory(source, owner)
        source.deleteWithImpact(owner, kind, empty)
        assertNull(CategoryService(source).get(owner, empty))
    }

    @Test fun `page outside edits and new links after the impact read reject the delete`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val category = insertCustomCategory(source, owner)
        val items = List(3) { seedItem(source, owner, ItemSeed(customCategory = category)) }
        val firstPage = source.impact(owner, kind, category, limit = 1)
        val outside = items.first { it != firstPage.items.single().id }
        ItemMutationService(source).edit(owner, outside, 3, ItemChanges(brand = FieldChange.Set(null)))
        val snapshot = itemSnapshot(source, items)
        expectError("CATEGORY_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, category, 1, firstPage.impactToken) }
        val second = source.impact(owner, kind, category)
        seedItem(source, owner, ItemSeed(customCategory = category))
        expectError("CATEGORY_DELETION_IMPACT_CONFLICT") { source.deleteStructure(owner, kind, category, 1, second.impactToken) }
        assertEquals(snapshot, itemSnapshot(source, items)); assertEquals(1, categoryVersion(source, category))
        source.deleteWithImpact(owner, kind, category)
        assertEquals(4, analysisScalar(source, "select count(*) from wishlist_items where category_missing_reason='CUSTOM_CATEGORY_DELETED'")!!.toInt())
    }

    @Test fun `pending job claims the post deletion version`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        for (lane in AnalysisLane.entries) {
            val category = insertCustomCategory(source, owner)
            val job = processingItem(source, owner, lane, "custom_category_id='$category',category_source='AI',category_missing_reason=null")
            val outbox = outboxCount(source, job.itemId)
            source.deleteWithImpact(owner, kind, category)
            assertEquals("PROCESSING", classification(source, job.itemId)["analysis_status"], "$lane deletion keeps analysis")
            assertEquals("${lane.name}_PENDING", jobStage(source, job.jobId))

            val claim = claimJob(source, job.jobId, lane)
            assertEquals(2, claim.expectedItemVersion, "$lane claims the version written by the deletion")
            // The model saw the category before deletion; its result must not bring the reference back.
            savePending(source, claim, category.toString())
            assertEquals(WorkerDisposition.ACKNOWLEDGE, finish(source, claim))
            val after = classification(source, job.itemId)
            assertNull(after["custom_category_id"], "$lane"); assertNull(after["category_id"]); assertNull(after["category_source"])
            assertEquals("CUSTOM_CATEGORY_DELETED", after["category_missing_reason"])
            assertTrue("CATEGORY" in after["user_override_fields"]!!)
            assertEquals("PARTIAL", after["analysis_status"], "$lane: no category is left, so the success is partial")
            assertEquals("PARTIAL", jobStage(source, job.jobId))
            assertEquals("AI name", itemColumns(source, job.itemId, "product_name")["product_name"], "$lane metadata is applied")
            assertEquals(outbox, outboxCount(source, job.itemId), "$lane no replacement job")

            val publicCategory = insertCustomCategory(source, owner)
            val publicJob = processingItem(source, owner, lane, "custom_category_id='$publicCategory',category_source='AI',category_missing_reason=null")
            source.deleteWithImpact(owner, kind, publicCategory)
            val publicClaim = claimJob(source, publicJob.jobId, lane)
            savePending(source, publicClaim, "C026")
            finish(source, publicClaim)
            val publicAfter = classification(source, publicJob.itemId)
            assertNull(publicAfter["category_id"], "$lane: the override also keeps a public AI result out")
            assertEquals("CUSTOM_CATEGORY_DELETED", publicAfter["category_missing_reason"])
            assertEquals("PARTIAL", publicAfter["analysis_status"])
        }
    }
}
