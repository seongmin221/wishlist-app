package app.wishlist

import app.category.CategoryRef
import app.common.FieldChange
import java.util.UUID
import kotlin.test.*

class ItemChangePlanTest {
    private val purposeA = UUID.fromString("00000000-0000-4000-8000-00000000000a")
    private val purposeB = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    private val mediaA = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val mediaB = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val custom = UUID.fromString("00000000-0000-4000-8000-0000000000c1")

    private val ready = ItemRow(
        name = "AI 이름", nameSource = ValueSource.AI, brand = "AI 브랜드",
        publicCategoryId = "C026", customCategoryId = null, categorySource = ValueSource.AI, categoryMissingReason = null,
        purposeId = purposeA, purposeSource = ValueSource.AI, mediaId = null, imageSource = ValueSource.AI,
        productImageUrl = "https://shop.example/a.jpg", overrides = emptySet(), reviewStatus = ReviewStatus.PENDING,
    )

    private fun edit(row: ItemRow, changes: ItemChanges) = ItemChangePlan.plan(row, changes, ItemCommand.Edit)

    @Test fun `same values are no-ops and keep AI sources`() {
        val plan = edit(ready, ItemChanges(
            name = FieldChange.Set("AI 이름"), brand = FieldChange.Set("AI 브랜드"),
            category = FieldChange.Set(CategoryRef.Public("C026")), purpose = FieldChange.Set(purposeA),
        ))
        assertFalse(plan.changed)
        assertEquals(ready, plan.next)
        assertEquals(emptySet(), plan.textFields)
        assertNull(plan.categoryToCheck); assertNull(plan.purposeToCheck); assertNull(plan.mediaToLink)
        assertNull(plan.purposeTransition)
    }

    @Test fun `changed name and brand become user values with overrides`() {
        val plan = edit(ready, ItemChanges(name = FieldChange.Set("내 이름"), brand = FieldChange.Set("내 브랜드")))
        assertTrue(plan.changed)
        assertEquals("내 이름", plan.next.name); assertEquals(ValueSource.USER, plan.next.nameSource)
        assertEquals("내 브랜드", plan.next.brand)
        assertEquals(setOf("NAME", "BRAND"), plan.next.overrides)
        assertEquals(setOf("name", "brand"), plan.textFields)
        assertEquals(ReviewStatus.PENDING, plan.next.reviewStatus, "name and brand alone keep review")
    }

    @Test fun `brand null clears with an override but null on null adds nothing`() {
        val cleared = edit(ready, ItemChanges(brand = FieldChange.Set(null)))
        assertNull(cleared.next.brand); assertEquals(setOf("BRAND"), cleared.next.overrides); assertTrue(cleared.changed)
        assertEquals(emptySet(), cleared.textFields)
        val empty = edit(ready.copy(brand = null), ItemChanges(brand = FieldChange.Set(null)))
        assertFalse(empty.changed); assertEquals(emptySet(), empty.next.overrides)
    }

    @Test fun `category change fills one column and clears the missing reason`() {
        val reassigned = edit(ready.copy(publicCategoryId = null, categorySource = null,
            categoryMissingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED, reviewStatus = ReviewStatus.CONFIRMED),
            ItemChanges(category = FieldChange.Set(CategoryRef.Custom(custom))))
        assertEquals(custom, reassigned.next.customCategoryId); assertNull(reassigned.next.publicCategoryId)
        assertEquals(ValueSource.USER, reassigned.next.categorySource); assertNull(reassigned.next.categoryMissingReason)
        assertEquals(CategoryRef.Custom(custom), reassigned.categoryToCheck)
        val toPublic = edit(ready.copy(publicCategoryId = null, customCategoryId = custom),
            ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001"))))
        assertEquals("C001", toPublic.next.publicCategoryId); assertNull(toPublic.next.customCategoryId)
    }

    @Test fun `purpose null records an explicit user unassignment once`() {
        for (source in listOf(ValueSource.UNASSIGNED, ValueSource.AI)) {
            val row = ready.copy(purposeId = if (source == ValueSource.AI) purposeA else null, purposeSource = source)
            val plan = edit(row, ItemChanges(purpose = FieldChange.Set(null)))
            assertTrue(plan.changed, "$source")
            assertNull(plan.next.purposeId); assertEquals(ValueSource.USER, plan.next.purposeSource)
            assertEquals(if (source == ValueSource.AI) purposeA to null else null, plan.purposeTransition)
        }
        val already = edit(ready.copy(purposeId = null, purposeSource = ValueSource.USER), ItemChanges(purpose = FieldChange.Set(null)))
        assertFalse(already.changed)
        val moved = edit(ready, ItemChanges(purpose = FieldChange.Set(purposeB)))
        assertEquals(purposeB, moved.next.purposeId); assertEquals(ValueSource.USER, moved.next.purposeSource)
        assertEquals(purposeA to purposeB, moved.purposeTransition); assertEquals(purposeB, moved.purposeToCheck)
    }

    @Test fun `linking a photo uses the user source without an image override`() {
        val linked = edit(ready, ItemChanges(media = FieldChange.Set(mediaA)))
        assertEquals(mediaA, linked.next.mediaId); assertEquals(ValueSource.USER, linked.next.imageSource)
        assertEquals(emptySet(), linked.next.overrides)
        assertEquals(mediaA, linked.mediaToLink); assertNull(linked.mediaToRelease)
        val swapped = edit(linked.next, ItemChanges(media = FieldChange.Set(mediaB)))
        assertEquals(mediaB, swapped.mediaToLink); assertEquals(mediaA, swapped.mediaToRelease)
        assertFalse(edit(linked.next, ItemChanges(media = FieldChange.Set(mediaA))).changed)
    }

    @Test fun `unlinking a photo returns to the analysed image or to none`() {
        val linked = ready.copy(mediaId = mediaA, imageSource = ValueSource.USER, overrides = setOf("IMAGE"))
        val back = edit(linked, ItemChanges(media = FieldChange.Set(null)))
        assertNull(back.next.mediaId); assertEquals(ValueSource.AI, back.next.imageSource)
        assertEquals(emptySet(), back.next.overrides); assertEquals(mediaA, back.mediaToRelease)
        val none = edit(linked.copy(productImageUrl = null), ItemChanges(media = FieldChange.Set(null)))
        assertNull(none.next.imageSource)
        assertFalse(edit(ready, ItemChanges(media = FieldChange.Set(null))).changed)
    }

    @Test fun `review after edit confirms only when no AI connection remains`() {
        data class Case(val row: ItemRow, val changes: ItemChanges, val expected: ReviewStatus)
        val userCategory = FieldChange.Set<CategoryRef>(CategoryRef.Public("C001"))
        val noAiPurpose = ready.copy(purposeId = null, purposeSource = ValueSource.UNASSIGNED)
        for ((i, case) in listOf(
            Case(noAiPurpose, ItemChanges(category = userCategory), ReviewStatus.CONFIRMED),
            Case(ready, ItemChanges(category = userCategory), ReviewStatus.PENDING),
            Case(ready, ItemChanges(category = userCategory, purpose = FieldChange.Set(purposeB)), ReviewStatus.CONFIRMED),
            Case(ready.copy(categorySource = ValueSource.USER), ItemChanges(purpose = FieldChange.Set(null)), ReviewStatus.CONFIRMED),
            Case(ready.copy(reviewStatus = ReviewStatus.DEFERRED), ItemChanges(category = userCategory, purpose = FieldChange.Set(null)), ReviewStatus.CONFIRMED),
            Case(ready.copy(reviewStatus = ReviewStatus.DEFERRED), ItemChanges(category = userCategory), ReviewStatus.DEFERRED),
            Case(noAiPurpose.copy(categorySource = ValueSource.USER), ItemChanges(name = FieldChange.Set("새 이름")), ReviewStatus.PENDING),
            Case(noAiPurpose.copy(reviewStatus = ReviewStatus.NOT_REQUIRED), ItemChanges(category = userCategory), ReviewStatus.NOT_REQUIRED),
            Case(noAiPurpose.copy(reviewStatus = ReviewStatus.CONFIRMED), ItemChanges(category = userCategory), ReviewStatus.CONFIRMED),
            // An AI source without a stored category (left after B8 deletion) is not an AI connection.
            Case(noAiPurpose.copy(publicCategoryId = null, categoryMissingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED),
                ItemChanges(purpose = FieldChange.Set(purposeB)), ReviewStatus.CONFIRMED),
        ).withIndex()) assertEquals(case.expected, edit(case.row, case.changes).next.reviewStatus, "case $i")
    }

    @Test fun `text rules apply only to values that differ from the stored ones`() {
        val extracted = "가".repeat(230) + "\n옵션"
        val row = ready.copy(name = extracted)
        assertEquals(emptySet(), ItemTextRules.invalidFields(edit(row, ItemChanges(name = FieldChange.Set(extracted)))))
        for ((value, valid) in listOf(
            "가".repeat(200) to true, "😀".repeat(200) to true, "가".repeat(201) to false, "  " to false,
            "줄\n바꿈" to false, "탭\t" to false, "제어\u0007" to false, "방향‮" to false,
        )) {
            val plan = edit(row, ItemChanges(name = FieldChange.Set(value), brand = FieldChange.Set(value)))
            assertEquals(if (valid) emptySet() else setOf("brand", "name"), ItemTextRules.invalidFields(plan), value)
        }
        assertEquals(setOf("brand"), ItemTextRules.invalidFields(edit(ready, ItemChanges(brand = FieldChange.Set("")))))
    }

    @Test fun `manual completion records name and category as user values even when unchanged`() {
        val partial = ready.copy(name = "가".repeat(230) + "\n옵션", publicCategoryId = "C026", categorySource = ValueSource.AI,
            reviewStatus = ReviewStatus.NOT_REQUIRED, purposeId = null, purposeSource = ValueSource.UNASSIGNED)
        val plan = ItemChangePlan.plan(partial, ItemChanges(name = FieldChange.Set(partial.name!!),
            category = FieldChange.Set(CategoryRef.Public("C026"))), ItemCommand.ManualCompletion)
        assertTrue(plan.changed)
        assertEquals(ValueSource.USER, plan.next.nameSource); assertEquals(ValueSource.USER, plan.next.categorySource)
        assertEquals(setOf("NAME"), plan.next.overrides)
        assertEquals(ReviewStatus.CONFIRMED, plan.next.reviewStatus)
        assertEquals(emptySet(), ItemTextRules.invalidFields(plan))
        assertNull(plan.categoryToCheck, "an unchanged category is not revalidated")
    }

    @Test fun `review decisions set the status and keep unchanged AI sources`() {
        val confirm = ItemChangePlan.plan(ready, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C026"))),
            ItemCommand.Review(ReviewDecision.CONFIRM))
        assertEquals(ReviewStatus.CONFIRMED, confirm.next.reviewStatus); assertEquals(ValueSource.AI, confirm.next.categorySource)
        val defer = ItemChangePlan.plan(ready, ItemChanges(category = FieldChange.Set(CategoryRef.Public("C001")),
            purpose = FieldChange.Set(null)), ItemCommand.Review(ReviewDecision.DEFER))
        assertEquals(ReviewStatus.DEFERRED, defer.next.reviewStatus)
        assertEquals(ValueSource.USER, defer.next.categorySource); assertEquals(ValueSource.USER, defer.next.purposeSource)
        assertTrue(defer.changed)
    }
}
