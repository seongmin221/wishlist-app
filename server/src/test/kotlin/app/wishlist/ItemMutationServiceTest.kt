package app.wishlist

import app.category.CategoryRef
import app.common.FieldChange
import app.testutil.*
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class ItemMutationServiceTest {
    private fun DataSource.service() = ItemMutationService(this)

    private fun expectError(code: String, block: () -> Unit): ItemMutationException =
        assertFailsWith<ItemMutationException> { block() }.also { assertEquals(code, it.code) }

    @Test fun `edit saves every user field in one versioned update and treats same values as no-ops`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "출퇴근", null)
        val (media, _) = insertMedia(source, owner)
        val custom = insertCustomCategory(source, owner)
        val item = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val purposeBefore = purposeColumns(source, purpose)

        val unchanged = source.service().edit(owner, item, 3, ItemChanges(
            name = FieldChange.Set("AI 이름"), category = FieldChange.Set(CategoryRef.Public("C026")), purpose = FieldChange.Set(purpose),
        ))
        assertEquals(3, unchanged.version)
        assertEquals(ValueSource.AI, unchanged.storedState.nameSource)
        assertEquals(purposeBefore, purposeColumns(source, purpose))

        val edited = source.service().edit(owner, item, 3, ItemChanges(
            name = FieldChange.Set("내 헤드폰"), brand = FieldChange.Set(null), category = FieldChange.Set(CategoryRef.Custom(custom)),
            purpose = FieldChange.Set(null), media = FieldChange.Set(media),
        ))
        assertEquals(4, edited.version)
        assertEquals("내 헤드폰", edited.storedState.state.productName)
        assertEquals(ValueSource.USER, edited.storedState.nameSource)
        assertNull(edited.brand)
        assertEquals(custom.toString(), edited.storedState.state.categoryId)
        assertEquals(ValueSource.USER, edited.storedState.categorySource)
        assertNull(edited.storedState.purposeId); assertEquals(ValueSource.USER, edited.storedState.purposeSource)
        assertEquals(media, edited.mediaId); assertEquals(ValueSource.USER, edited.storedState.imageSource)
        assertEquals(ReviewStatus.CONFIRMED, edited.storedState.state.reviewStatus, "no AI connection remains")
        assertEquals(setOf("NAME", "BRAND"), edited.storedState.userOverrideFields)
        assertEquals(itemColumns(source, item, "category_id")["category_id"], null)
        assertNull(itemColumns(source, item, "manual_completion_at")["manual_completion_at"])
        val after = purposeColumns(source, purpose)
        assertEquals((purposeBefore["membership"]!!.toInt() + 1).toString(), after["membership"])
        assertEquals(purposeBefore["activity"], after["activity"], "removal does not count as activity")
        assertNull(mediaColumns(source, media)["unreferenced"])
    }

    @Test fun `purpose changes update membership and activity of the new purpose only`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val from = insertPurpose(source, owner, "A", null); val to = insertPurpose(source, owner, "B", null)
        val item = seedItem(source, owner, ItemSeed(purpose = from, purposeSource = "USER", review = "CONFIRMED"))
        val fromBefore = purposeColumns(source, from); val toBefore = purposeColumns(source, to)
        val moved = source.service().edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(to)))
        assertEquals(to.toString(), moved.storedState.purposeId)
        val fromAfter = purposeColumns(source, from); val toAfter = purposeColumns(source, to)
        assertEquals(fromBefore["membership"]!!.toInt() + 1, fromAfter["membership"]!!.toInt()); assertEquals(fromBefore["activity"], fromAfter["activity"])
        assertEquals(toBefore["membership"]!!.toInt() + 1, toAfter["membership"]!!.toInt())
        assertNotEquals(toBefore["activity"], toAfter["activity"]); assertEquals("CANDIDATE_ADDED", toAfter["kind"])

        val unassigned = seedItem(source, owner, ItemSeed(review = "NOT_REQUIRED"))
        val confirmedEmpty = source.service().edit(owner, unassigned, 3, ItemChanges(purpose = FieldChange.Set(null)))
        assertEquals(4, confirmedEmpty.version); assertEquals(ValueSource.USER, confirmedEmpty.storedState.purposeSource)
        assertEquals(4, source.service().edit(owner, unassigned, 4, ItemChanges(purpose = FieldChange.Set(null))).version, "already user unassigned")
    }

    @Test fun `photo replacement and removal maintain media references`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val (first, _) = insertMedia(source, owner); val (second, _) = insertMedia(source, owner)
        val item = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        source.service().edit(owner, item, 3, ItemChanges(media = FieldChange.Set(first)))
        val firstLinked = mediaColumns(source, first)
        assertNull(firstLinked["unreferenced"])

        val swapped = source.service().edit(owner, item, 4, ItemChanges(media = FieldChange.Set(second)))
        assertEquals(second, swapped.mediaId)
        assertNotNull(mediaColumns(source, first)["unreferenced"]); assertNotEquals(firstLinked["updated"], mediaColumns(source, first)["updated"])
        assertNull(mediaColumns(source, second)["unreferenced"])

        val removed = source.service().edit(owner, item, 5, ItemChanges(media = FieldChange.Set(null)))
        assertNull(removed.mediaId); assertEquals(ValueSource.AI, removed.storedState.imageSource)
        assertEquals("https://shop.example/a.jpg", removed.productImageUrl)
        assertNotNull(mediaColumns(source, second)["unreferenced"])

        val noExternal = seedItem(source, owner, ItemSeed(review = "CONFIRMED", imageUrl = null, imageSource = null))
        val (third, _) = insertMedia(source, owner)
        source.service().edit(owner, noExternal, 3, ItemChanges(media = FieldChange.Set(third)))
        assertNull(source.service().edit(owner, noExternal, 4, ItemChanges(media = FieldChange.Set(null))).storedState.imageSource)
    }

    @Test fun `edit rejects missing items stale versions and disallowed states before reference errors`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        val archivedPurpose = insertPurpose(source, owner, "보관", null)
        analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='$archivedPurpose'")
        val item = seedItem(source, owner)
        val service = source.service()
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.edit(owner, UUID.randomUUID(), 3, ItemChanges(name = FieldChange.Set("a"))) }
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.edit(other, item, 3, ItemChanges(name = FieldChange.Set("a"))) }
        val deleted = seedItem(source, owner, ItemSeed(lifecycle = "DELETED"))
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.edit(owner, deleted, 3, ItemChanges(name = FieldChange.Set("a"))) }

        val stale = expectError("WISHLIST_ITEM_VERSION_CONFLICT") {
            service.edit(owner, item, 2, ItemChanges(purpose = FieldChange.Set(archivedPurpose)))
        }
        assertEquals(3, stale.currentVersion)
        val processing = seedItem(source, owner, ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"))
        expectError("WISHLIST_ITEM_VERSION_CONFLICT") { service.edit(owner, processing, 9, ItemChanges(name = FieldChange.Set("a"))) }
        assertEquals(3, expectError("WISHLIST_ITEM_STATE_CONFLICT") {
            service.edit(owner, processing, 3, ItemChanges(name = FieldChange.Set("a")))
        }.currentVersion)
        val archived = seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED"))
        expectError("WISHLIST_ITEM_STATE_CONFLICT") { service.edit(owner, archived, 3, ItemChanges(name = FieldChange.Set("a"))) }
        assertEquals("3", itemColumns(source, item, "version")["version"])
    }

    @Test fun `edit validates changed text and references in a fixed order and rolls back`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        val service = source.service()
        val purpose = insertPurpose(source, owner, "A", null)
        val foreignPurpose = insertPurpose(source, other, "B", null)
        val archivedPurpose = insertPurpose(source, owner, "보관", null)
        analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='$archivedPurpose'")
        val deletedCategory = insertCustomCategory(source, owner, deleted = true)
        val foreignCategory = insertCustomCategory(source, other)
        val (linked, _) = insertMedia(source, owner)
        val (pending, _) = insertMedia(source, owner, "PENDING")
        val (failed, _) = insertMedia(source, owner, "FAILED")
        val (deleting, _) = insertMedia(source, owner, "DELETING")
        val (foreignMedia, _) = insertMedia(source, other)
        seedItem(source, owner, ItemSeed(media = linked, imageSource = "USER", review = "CONFIRMED"))
        val extracted = "가".repeat(230) + "\n옵션"
        val item = seedItem(source, owner, ItemSeed(name = extracted, purpose = purpose, purposeSource = "AI"))
        val before = itemColumns(source, item, "version", "product_name", "purpose_id", "media_asset_id")
        val purposeBefore = purposeColumns(source, purpose)

        assertEquals(setOf("brand", "name"), expectError("INVALID_WISHLIST_ITEM_INPUT") {
            service.edit(owner, item, 3, ItemChanges(name = FieldChange.Set("가".repeat(201)), brand = FieldChange.Set("")))
        }.fields)
        assertEquals(3, service.edit(owner, item, 3, ItemChanges(name = FieldChange.Set(extracted))).version, "unchanged extracted name")
        expectError("CATEGORY_NOT_AVAILABLE") {
            service.edit(owner, item, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Custom(deletedCategory)),
                purpose = FieldChange.Set(archivedPurpose), media = FieldChange.Set(pending)))
        }
        expectError("CATEGORY_NOT_AVAILABLE") { service.edit(owner, item, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Custom(foreignCategory)))) }
        expectError("PURPOSE_NOT_AVAILABLE") {
            service.edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(archivedPurpose), media = FieldChange.Set(pending)))
        }
        expectError("PURPOSE_NOT_AVAILABLE") { service.edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(foreignPurpose))) }
        expectError("PURPOSE_NOT_AVAILABLE") { service.edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(UUID.randomUUID()))) }
        for (media in listOf(pending, failed, deleting, foreignMedia, linked, UUID.randomUUID())) {
            expectError("MEDIA_NOT_AVAILABLE") {
                service.edit(owner, item, 3, ItemChanges(name = FieldChange.Set("새 이름"), purpose = FieldChange.Set(null), media = FieldChange.Set(media)))
            }
        }
        assertEquals(before, itemColumns(source, item, "version", "product_name", "purpose_id", "media_asset_id"))
        assertEquals(purposeBefore, purposeColumns(source, purpose))
    }

    @Test fun `edit reassigns deleted categories and keeps completion and reanalysis available`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = source.service()
        val reassign = seedItem(source, owner, ItemSeed(review = "CONFIRMED", category = null, categorySource = null,
            missingReason = "CUSTOM_CATEGORY_DELETED"))
        assertEquals(WishlistItemPolicy.evaluate(GetWishlistItemService(source).get(owner, reassign)!!.storedState.state).requiredAction,
            RequiredAction.CATEGORY_REASSIGNMENT)
        val reassigned = service.edit(owner, reassign, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001"))))
        assertEquals(RequiredAction.NONE, WishlistItemPolicy.evaluate(reassigned.storedState.state).requiredAction)
        assertNull(reassigned.storedState.state.manualCompletionAt)

        for (status in listOf("PARTIAL", "FAILED_RETRYABLE")) {
            val failing = seedItem(source, owner, ItemSeed(analysis = status, review = "NOT_REQUIRED", name = null, nameSource = null,
                category = null, categorySource = null, missingReason = "EXTRACTION_UNRESOLVED"))
            val filled = service.edit(owner, failing, 3, ItemChanges(name = FieldChange.Set("직접 이름"),
                category = FieldChange.Set(CategoryRef.Public("C026"))))
            val policy = WishlistItemPolicy.evaluate(filled.storedState.state)
            assertNull(filled.storedState.state.manualCompletionAt, status)
            assertEquals(RequiredAction.NONE, policy.requiredAction, status)
            assertTrue(ItemAction.MANUAL_COMPLETE in policy.allowedActions, status)
            assertEquals(status == "FAILED_RETRYABLE", ItemAction.REANALYZE in policy.allowedActions, status)
        }
    }

    @Test fun `edit keeps review while an AI connection remains and confirms when none remains`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "A", null)
        val service = source.service()
        val pending = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val categoryOnly = service.edit(owner, pending, 3, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001"))))
        assertEquals(ReviewStatus.PENDING, categoryOnly.storedState.state.reviewStatus)
        assertEquals(ReviewStatus.CONFIRMED, service.edit(owner, pending, 4, ItemChanges(purpose = FieldChange.Set(null))).storedState.state.reviewStatus)
        val deferred = seedItem(source, owner, ItemSeed(review = "DEFERRED"))
        assertEquals(ReviewStatus.DEFERRED, service.edit(owner, deferred, 3, ItemChanges(name = FieldChange.Set("이름만"))).storedState.state.reviewStatus)
        assertEquals(ReviewStatus.CONFIRMED,
            service.edit(owner, deferred, 4, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001")))).storedState.state.reviewStatus)
    }

    private fun reviewGroupIds(source: DataSource, owner: UUID): List<UUID> =
        (WishlistReadService(source).read(owner, ReadQuery(ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW), ReadWindow.Page(100)))
            as ReadResult.Success).page.items.map { it.id }

    @Test fun `manual completion confirms failed and partial items and keeps diagnostics`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = source.service()
        val purpose = insertPurpose(source, owner, "A", null)
        val (media, _) = insertMedia(source, owner)
        for ((status, failure) in listOf("PARTIAL" to "AI_ABSTAINED", "FAILED_RETRYABLE" to "ANALYSIS_RETRYABLE_FAILURE", "FAILED_TERMINAL" to "BLOCKED_ADDRESS")) {
            val item = seedItem(source, owner, ItemSeed(analysis = status, review = "NOT_REQUIRED", category = null, categorySource = null,
                missingReason = "AI_ABSTAINED", failureCode = failure, brand = "AI 브랜드"))
            analysisSql(source, "update wishlist_items set predicted_category_id='C001' where id='$item'")
            val done = service.completeManually(owner, item, 3, ItemChanges(name = FieldChange.Set("직접 이름"),
                category = FieldChange.Set(CategoryRef.Public("C026")), purpose = if (status == "PARTIAL") FieldChange.Set(purpose) else FieldChange.Keep,
                media = if (status == "PARTIAL") FieldChange.Set(media) else FieldChange.Keep))
            assertEquals(4, done.version, status)
            assertNotNull(done.storedState.state.manualCompletionAt)
            assertEquals(ReviewStatus.CONFIRMED, done.storedState.state.reviewStatus)
            assertEquals(AnalysisStatus.valueOf(status), done.storedState.state.analysisStatus)
            assertEquals(failure, done.analysisFailureCode)
            assertEquals("C001", itemColumns(source, item, "predicted_category_id")["predicted_category_id"])
            assertEquals(ValueSource.USER, done.storedState.nameSource); assertEquals(ValueSource.USER, done.storedState.categorySource)
            assertNull(done.storedState.state.categoryMissingReason)
            assertEquals("AI 브랜드", done.brand, "omitted optional fields stay")
            assertEquals(setOf(ItemAction.EDIT, ItemAction.DELETE), WishlistItemPolicy.evaluate(done.storedState.state).allowedActions)
            if (status == "PARTIAL") { assertEquals(purpose.toString(), done.storedState.purposeId); assertEquals(media, done.mediaId) }
            assertEquals(4, expectError("WISHLIST_ITEM_VERSION_CONFLICT") {
                service.completeManually(owner, item, 3, ItemChanges(name = FieldChange.Set("a"), category = FieldChange.Set(CategoryRef.Public("C026"))))
            }.currentVersion)
            expectError("WISHLIST_ITEM_STATE_CONFLICT") {
                service.completeManually(owner, item, 4, ItemChanges(name = FieldChange.Set("a"), category = FieldChange.Set(CategoryRef.Public("C026"))))
            }
        }
        for (seed in listOf(ItemSeed(), ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"))) {
            val item = seedItem(source, owner, seed)
            expectError("WISHLIST_ITEM_STATE_CONFLICT") {
                service.completeManually(owner, item, 3, ItemChanges(name = FieldChange.Set("a"), category = FieldChange.Set(CategoryRef.Public("C026"))))
            }
        }
    }

    @Test fun `manual completion accepts an unchanged long extracted name and records it as user input`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val extracted = "가".repeat(230) + "\n옵션"
        val item = seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", name = extracted, category = null,
            categorySource = null, missingReason = "AI_ABSTAINED"))
        val done = source.service().completeManually(owner, item, 3, ItemChanges(name = FieldChange.Set(extracted),
            category = FieldChange.Set(CategoryRef.Public("C026")), brand = FieldChange.Set(null)))
        assertEquals(extracted, done.storedState.state.productName)
        assertEquals(ValueSource.USER, done.storedState.nameSource)
        assertTrue("NAME" in done.storedState.userOverrideFields)
        assertNull(done.brand)
        expectError("INVALID_WISHLIST_ITEM_INPUT") {
            val other = seedItem(source, owner, ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED", category = null, categorySource = null,
                missingReason = "AI_ABSTAINED"))
            source.service().completeManually(owner, other, 3, ItemChanges(name = FieldChange.Set("가".repeat(201)),
                category = FieldChange.Set(CategoryRef.Public("C026"))))
        }
    }

    @Test fun `review decisions confirm or defer and deferred items do not come back`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = source.service()
        val purpose = insertPurpose(source, owner, "A", null)
        val confirm = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val defer = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        val sameValue = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "AI"))
        assertEquals(setOf(confirm, defer, sameValue), reviewGroupIds(source, owner).toSet())

        val confirmed = service.decideReview(owner, confirm, 3, ReviewDecision.CONFIRM, ItemChanges())
        assertEquals(ReviewStatus.CONFIRMED, confirmed.storedState.state.reviewStatus); assertEquals(4, confirmed.version)
        assertEquals(ValueSource.AI, confirmed.storedState.categorySource); assertEquals(ValueSource.AI, confirmed.storedState.purposeSource)

        val kept = service.decideReview(owner, sameValue, 3, ReviewDecision.CONFIRM, ItemChanges(
            category = FieldChange.Set(CategoryRef.Public("C026")), purpose = FieldChange.Set(purpose)))
        assertEquals(ValueSource.AI, kept.storedState.categorySource, "same value keeps the AI source")
        assertEquals(ValueSource.AI, kept.storedState.purposeSource)

        val deferred = service.decideReview(owner, defer, 3, ReviewDecision.DEFER, ItemChanges(
            category = FieldChange.Set(CategoryRef.Public("C001")), purpose = FieldChange.Set(null)))
        assertEquals(ReviewStatus.DEFERRED, deferred.storedState.state.reviewStatus)
        assertEquals(ValueSource.USER, deferred.storedState.categorySource)
        assertNull(deferred.storedState.purposeId); assertEquals(ValueSource.USER, deferred.storedState.purposeSource)
        assertEquals(emptyList(), reviewGroupIds(source, owner))
        assertEquals(ReviewStatus.DEFERRED, service.edit(owner, defer, 4, ItemChanges(name = FieldChange.Set("이름만"))).storedState.state.reviewStatus)
        assertEquals(emptyList(), reviewGroupIds(source, owner))

        expectError("WISHLIST_ITEM_STATE_CONFLICT") { service.decideReview(owner, confirm, 4, ReviewDecision.DEFER, ItemChanges()) }
        assertEquals(3, expectError("WISHLIST_ITEM_VERSION_CONFLICT") {
            service.decideReview(owner, seedItem(source, owner), 2, ReviewDecision.CONFIRM, ItemChanges())
        }.currentVersion)
        for (seed in listOf(
            ItemSeed(analysis = "PARTIAL"), ItemSeed(manualCompleted = true), ItemSeed(review = "NOT_REQUIRED"),
            ItemSeed(analysis = "PROCESSING"), ItemSeed(lifecycle = "ARCHIVED"),
        )) {
            val item = seedItem(source, owner, seed)
            expectError("WISHLIST_ITEM_STATE_CONFLICT") { service.decideReview(owner, item, 3, ReviewDecision.CONFIRM, ItemChanges()) }
        }
        expectError("PURPOSE_NOT_AVAILABLE") {
            service.decideReview(owner, seedItem(source, owner), 3, ReviewDecision.CONFIRM, ItemChanges(purpose = FieldChange.Set(UUID.randomUUID())))
        }
    }
}
