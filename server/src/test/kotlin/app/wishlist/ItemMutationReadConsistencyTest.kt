package app.wishlist

import app.category.CategoryRef
import app.category.CategoryScope
import app.category.CategoryService
import app.common.FieldChange
import app.home.HomeReadService
import app.purpose.PurposeProjection
import app.purpose.PurposeService
import app.testutil.*
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class ItemMutationReadConsistencyTest {
    private fun page(source: DataSource, owner: UUID, scope: ReadScope): ReadPage =
        (WishlistReadService(source).read(owner, ReadQuery(scope, ReadWindow.Page(100))) as ReadResult.Success).page

    private fun ids(source: DataSource, owner: UUID, scope: ReadScope) = page(source, owner, scope).let { p ->
        assertEquals(p.items.size.toLong(), p.totalCount, "totalCount matches the listed items for $scope"); p.items.map { it.id }.toSet()
    }

    private fun homeCounts(source: DataSource, owner: UUID): Map<HomeActionGroup, Long> =
        HomeReadService(source).get(owner).actionGroups.associate { it.group to it.count }.filterValues { it > 0 }

    private fun homeGroup(source: DataSource, owner: UUID, group: HomeActionGroup) =
        HomeReadService(source).get(owner).actionGroups.single { it.group == group }.let { g -> g.count to g.items.map { it.id } }

    private fun position(source: DataSource, owner: UUID, id: UUID) =
        assertNotNull(WishlistItemRepository(source).findOwned(owner, id)).let { ReadPosition(it.createdAt, it.id) }

    @Test fun `lists home purposes and categories follow every mutation`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val mutations = ItemMutationService(source)
        val a = insertPurpose(source, owner, "A", null); val b = insertPurpose(source, owner, "B", null)
        val custom = insertCustomCategory(source, owner)
        val review = seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "AI"))
        val ready = seedItem(source, owner, ItemSeed(review = "CONFIRMED", purpose = a, purposeSource = "USER"))
        val partial = seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", name = null, nameSource = null,
            category = null, categorySource = null, missingReason = "EXTRACTION_UNRESOLVED"))
        val failed = seedItem(source, owner, ItemSeed(analysis = "FAILED_RETRYABLE", review = "NOT_REQUIRED", name = null, nameSource = null,
            category = null, categorySource = null, missingReason = "EXTRACTION_UNRESOLVED"))
        CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/new")
        assertEquals(mapOf(HomeActionGroup.ANALYSIS_IN_PROGRESS to 1L, HomeActionGroup.INFORMATION_COMPLETION to 2L,
            HomeActionGroup.CLASSIFICATION_REVIEW to 1L), homeCounts(source, owner))
        assertEquals(setOf(review, ready), ids(source, owner, ReadScope.Category(CategoryRef.Public("C026"))))

        // Review confirm leaves the review group but stays in the category list.
        mutations.decideReview(owner, review, 3, ReviewDecision.CONFIRM, ItemChanges())
        assertNull(homeCounts(source, owner)[HomeActionGroup.CLASSIFICATION_REVIEW])
        assertEquals(setOf(review, ready), ids(source, owner, ReadScope.Category(CategoryRef.Public("C026"))))

        // Category and purpose edits move the item between lists and purpose counts.
        mutations.edit(owner, ready, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Custom(custom)), purpose = FieldChange.Set(b)))
        assertEquals(setOf(review), ids(source, owner, ReadScope.Category(CategoryRef.Public("C026"))))
        assertEquals(setOf(ready), ids(source, owner, ReadScope.Category(CategoryRef.Custom(custom))))
        assertEquals(setOf(review), ids(source, owner, ReadScope.Purpose(a)))
        assertEquals(setOf(ready), ids(source, owner, ReadScope.Purpose(b)))
        val purposes = PurposeService(source).list(owner, PurposeProjection.SUMMARY, 10, null).entries.map { it.purpose }
        assertEquals(listOf(b, a), purposes.map { it.id }, "the purpose that gained a candidate is the most recent")
        assertEquals(listOf(1L, 1L), purposes.map { it.candidateCount })
        assertEquals(listOf(ready), purposes.first().let { PurposeService(source).list(owner, PurposeProjection.SUMMARY, 10, null)
            .entries.first().previews.map { it.itemId } })
        assertEquals(2, assertNotNull(PurposeService(source).get(owner, b)).membershipVersion)
        val browse = CategoryService(source).list(owner, CategoryScope.BROWSE, null).groups.flatMap { it.categories }
        assertEquals(1L, browse.single { it.id == custom.toString() }.itemCount)
        assertEquals(1L, browse.single { it.id == "C026" }.itemCount)

        // Purpose removal makes the item an explicit "no purpose".
        mutations.edit(owner, review, 4, ItemChanges(purpose = FieldChange.Set(null)))
        assertTrue(review in ids(source, owner, ReadScope.PurposeUnassigned))
        assertEquals(0L, assertNotNull(PurposeService(source).get(owner, a)).candidateCount)

        // Manual completion leaves information completion and joins the chosen category.
        mutations.completeManually(owner, partial, 3, ItemChanges(name = FieldChange.Set("직접"), category = FieldChange.Set(CategoryRef.Public("C026"))))
        assertEquals(1L, homeCounts(source, owner)[HomeActionGroup.INFORMATION_COMPLETION])
        assertEquals(setOf(review, partial), ids(source, owner, ReadScope.Category(CategoryRef.Public("C026"))))

        // Reanalysis moves the failed item to analysis in progress.
        ReanalysisService(source).reanalyze(owner, failed, UUID.randomUUID())
        assertEquals(mapOf(HomeActionGroup.ANALYSIS_IN_PROGRESS to 2L), homeCounts(source, owner))

        // Deletion leaves every list and count.
        mutations.delete(owner, ready)
        assertEquals(emptySet(), ids(source, owner, ReadScope.Category(CategoryRef.Custom(custom))))
        assertEquals(emptySet(), ids(source, owner, ReadScope.Purpose(b)))
        assertEquals(0L, assertNotNull(PurposeService(source).get(owner, b)).candidateCount)
        assertEquals(0L, CategoryService(source).list(owner, CategoryScope.BROWSE, null).groups.flatMap { it.categories }
            .single { it.id == custom.toString() }.itemCount)
        val (count, previews) = homeGroup(source, owner, HomeActionGroup.ANALYSIS_IN_PROGRESS)
        assertEquals(2L, count); assertEquals(2, previews.size)
    }

    @Test fun `anchors recover after the anchor item is deleted or moved to another scope`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val items = (1..4).map { seedItem(source, owner, ItemSeed(review = "CONFIRMED")) }
        val scope = ReadScope.Category(CategoryRef.Public("C026"))
        val newestFirst = page(source, owner, scope).items.map { it.id }
        assertEquals(items.toSet(), newestFirst.toSet())

        val deleted = newestFirst[1]
        val deletedPosition = position(source, owner, deleted)
        ItemMutationService(source).delete(owner, deleted)
        val afterDelete = (WishlistReadService(source).read(owner, ReadQuery(scope, ReadWindow.Anchor(deletedPosition, 0, 0))) as ReadResult.Success).page
        assertEquals(false, afterDelete.anchorResolved)
        assertEquals(newestFirst[2], afterDelete.resolvedAnchorItemId, "the next older item takes the place")

        val moved = newestFirst[2]
        val movedPosition = position(source, owner, moved)
        ItemMutationService(source).edit(owner, moved, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001"))))
        val afterMove = (WishlistReadService(source).read(owner, ReadQuery(scope, ReadWindow.Anchor(movedPosition, 0, 0))) as ReadResult.Success).page
        assertEquals(false, afterMove.anchorResolved)
        assertEquals(newestFirst[3], afterMove.resolvedAnchorItemId)

        // A home review window recovers the same way once its anchor is confirmed.
        val reviewItems = (1..3).map { seedItem(source, owner) }
        val reviewScope = ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW)
        val reviewOrder = page(source, owner, reviewScope).items.map { it.id }
        assertEquals(reviewItems.toSet(), reviewOrder.toSet())
        val confirmedPosition = position(source, owner, reviewOrder[0])
        ItemMutationService(source).decideReview(owner, reviewOrder[0], 3, ReviewDecision.CONFIRM, ItemChanges())
        val afterReview = (WishlistReadService(source).read(owner, ReadQuery(reviewScope, ReadWindow.Anchor(confirmedPosition))) as ReadResult.Success).page
        assertEquals(false, afterReview.anchorResolved)
        assertEquals(reviewOrder[1], afterReview.resolvedAnchorItemId)
        assertEquals(reviewOrder.drop(1), afterReview.items.map { it.id })
    }
}
