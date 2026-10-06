package app.wishlist

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WishlistItemPolicyTest {
    private val editable = setOf(ItemAction.EDIT, ItemAction.DELETE)

    @Test
    fun `processing permits only deletion even when incomplete or pending review`() {
        assertPolicy(
            state(analysis = AnalysisStatus.PROCESSING, name = null, category = null, review = ReviewStatus.PENDING),
            RequiredAction.ANALYSIS_IN_PROGRESS, HomeActionGroup.ANALYSIS_IN_PROGRESS, setOf(ItemAction.DELETE),
        )
    }

    @Test
    fun `deleted category on ready item uses editing rather than manual completion`() {
        assertPolicy(
            state(category = null, missing = CategoryMissingReason.CUSTOM_CATEGORY_DELETED),
            RequiredAction.CATEGORY_REASSIGNMENT, HomeActionGroup.INFORMATION_COMPLETION, editable,
        )
    }

    @Test
    fun `AI abstention and unusable response require category assignment in completion group`() {
        for (reason in listOf(CategoryMissingReason.AI_ABSTAINED, CategoryMissingReason.AI_RESPONSE_UNUSABLE)) {
            assertPolicy(
                state(analysis = AnalysisStatus.PARTIAL, category = null, missing = reason),
                RequiredAction.CATEGORY_ASSIGNMENT, HomeActionGroup.INFORMATION_COMPLETION,
                editable + ItemAction.MANUAL_COMPLETE,
            )
        }
    }

    @Test
    fun `retryable failure permits manual completion and retry before manual completion`() {
        assertPolicy(
            state(analysis = AnalysisStatus.FAILED_RETRYABLE, name = null, category = null),
            RequiredAction.INFORMATION_COMPLETION, HomeActionGroup.INFORMATION_COMPLETION,
            editable + setOf(ItemAction.MANUAL_COMPLETE, ItemAction.REANALYZE),
        )
    }

    @Test
    fun `terminal failure permits completion without retry`() {
        assertPolicy(
            state(analysis = AnalysisStatus.FAILED_TERMINAL, name = null, category = null),
            RequiredAction.INFORMATION_COMPLETION, HomeActionGroup.INFORMATION_COMPLETION,
            editable + ItemAction.MANUAL_COMPLETE,
        )
    }

    @Test
    fun `manual completion keeps original analysis status and prevents another completion or retry`() {
        for (analysis in listOf(AnalysisStatus.PARTIAL, AnalysisStatus.FAILED_RETRYABLE, AnalysisStatus.FAILED_TERMINAL)) {
            val original = state(analysis = analysis, completed = Instant.parse("2026-10-05T00:00:00Z"))
            assertPolicy(original, RequiredAction.NONE, null, editable)
            assertEquals(analysis, original.analysisStatus)
        }
    }

    @Test
    fun `ready pending review with complete minimum information permits review`() {
        assertPolicy(
            state(review = ReviewStatus.PENDING),
            RequiredAction.CLASSIFICATION_REVIEW, HomeActionGroup.CLASSIFICATION_REVIEW,
            editable + ItemAction.REVIEW,
        )
    }

    @Test
    fun `confirmed and deferred review do not reappear in home review group`() {
        for (review in listOf(ReviewStatus.CONFIRMED, ReviewStatus.DEFERRED, ReviewStatus.NOT_REQUIRED)) {
            assertPolicy(state(review = review), RequiredAction.NONE, null, editable)
        }
    }

    @Test
    fun `archived and deleted override every analysis and review status`() {
        for (lifecycle in listOf(LifecycleStatus.ARCHIVED, LifecycleStatus.DELETED)) {
            for (analysis in AnalysisStatus.entries) {
                for (review in ReviewStatus.entries) {
                    assertPolicy(
                        state(analysis = analysis, review = review, lifecycle = lifecycle, name = null, category = null),
                        RequiredAction.NONE, null, emptySet(),
                    )
                }
            }
        }
    }

    @Test
    fun `missing or blank product name takes precedence over category and review`() {
        for (name in listOf(null, "", "  \n")) {
            assertPolicy(
                state(name = name, category = null, missing = CategoryMissingReason.AI_ABSTAINED, review = ReviewStatus.PENDING),
                RequiredAction.INFORMATION_COMPLETION, HomeActionGroup.INFORMATION_COMPLETION, editable,
            )
        }
    }

    @Test
    fun `legacy category absence without reason is treated as missing information`() {
        for (analysis in listOf(AnalysisStatus.PARTIAL, AnalysisStatus.FAILED_RETRYABLE, AnalysisStatus.FAILED_TERMINAL)) {
            val actions = editable + ItemAction.MANUAL_COMPLETE +
                if (analysis == AnalysisStatus.FAILED_RETRYABLE) setOf(ItemAction.REANALYZE) else emptySet()
            assertPolicy(
                state(analysis = analysis, category = null),
                RequiredAction.INFORMATION_COMPLETION, HomeActionGroup.INFORMATION_COMPLETION, actions,
            )
        }
    }

    @Test
    fun `extraction unresolved requires information completion`() {
        assertPolicy(
            state(analysis = AnalysisStatus.PARTIAL, category = null, missing = CategoryMissingReason.EXTRACTION_UNRESOLVED),
            RequiredAction.INFORMATION_COMPLETION, HomeActionGroup.INFORMATION_COMPLETION,
            editable + ItemAction.MANUAL_COMPLETE,
        )
    }

    @Test
    fun `category deleted after manual completion still requires editing`() {
        assertPolicy(
            state(analysis = AnalysisStatus.PARTIAL, category = null, missing = CategoryMissingReason.CUSTOM_CATEGORY_DELETED,
                completed = Instant.parse("2026-10-05T00:00:00Z")),
            RequiredAction.CATEGORY_REASSIGNMENT, HomeActionGroup.INFORMATION_COMPLETION, editable,
        )
    }

    @Test
    fun `failure with retained metadata retains completion actions independently of home group`() {
        assertPolicy(
            state(analysis = AnalysisStatus.FAILED_RETRYABLE), RequiredAction.NONE, null,
            editable + setOf(ItemAction.MANUAL_COMPLETE, ItemAction.REANALYZE),
        )
    }

    private fun state(
        analysis: AnalysisStatus = AnalysisStatus.READY,
        review: ReviewStatus = ReviewStatus.CONFIRMED,
        lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE,
        name: String? = "헤드폰",
        category: String? = "C026",
        missing: CategoryMissingReason? = null,
        completed: Instant? = null,
    ) = WishlistItemState(analysis, review, lifecycle, name, category, missing, completed)

    private fun assertPolicy(
        state: WishlistItemState,
        required: RequiredAction,
        group: HomeActionGroup?,
        actions: Set<ItemAction>,
    ) {
        val actual = WishlistItemPolicy.evaluate(state)
        assertEquals(required, actual.requiredAction, state.toString())
        assertEquals(group, actual.homeActionGroup, state.toString())
        assertEquals(actions, actual.allowedActions, state.toString())
    }
}
