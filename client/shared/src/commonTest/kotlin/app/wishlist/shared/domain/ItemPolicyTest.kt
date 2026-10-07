package app.wishlist.shared.domain

import app.wishlist.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemPolicyTest {
    // Independent literal table from the merged B2 WishlistItemPolicy + server tests (1c6d949).
    // No expectation calls evaluateItem or homeActionGroup.
    private data class Case(val label: String, val item: WishlistItem, val required: RequiredAction, val actions: Set<ItemAction>)

    @Test
    fun fake_policy_matches_server_state_priority_and_actions() {
        val editable = setOf(ItemAction.EDIT, ItemAction.DELETE)
        val completable = setOf(ItemAction.EDIT, ItemAction.DELETE, ItemAction.MANUAL_COMPLETE)
        val retryable = setOf(ItemAction.EDIT, ItemAction.DELETE, ItemAction.MANUAL_COMPLETE, ItemAction.REANALYZE)
        val cases = listOf(
            Case("processing overrides incomplete pending review", itemFixture(analysis = AnalysisStatus.PROCESSING, name = null, categoryId = null, review = ReviewStatus.PENDING), RequiredAction.ANALYSIS_IN_PROGRESS, setOf(ItemAction.DELETE)),
            Case("ready deleted category uses edit", itemFixture(categoryId = null, missingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED), RequiredAction.CATEGORY_REASSIGNMENT, editable),
            Case("AI abstained", itemFixture(analysis = AnalysisStatus.PARTIAL, categoryId = null, missingReason = CategoryMissingReason.AI_ABSTAINED), RequiredAction.CATEGORY_ASSIGNMENT, completable),
            Case("unusable AI response", itemFixture(analysis = AnalysisStatus.PARTIAL, categoryId = null, missingReason = CategoryMissingReason.AI_RESPONSE_UNUSABLE), RequiredAction.CATEGORY_ASSIGNMENT, completable),
            Case("retryable missing information", itemFixture(analysis = AnalysisStatus.FAILED_RETRYABLE, name = null, categoryId = null), RequiredAction.INFORMATION_COMPLETION, retryable),
            Case("terminal missing information", itemFixture(analysis = AnalysisStatus.FAILED_TERMINAL, name = null, categoryId = null), RequiredAction.INFORMATION_COMPLETION, completable),
            Case("partial legacy no reason", itemFixture(analysis = AnalysisStatus.PARTIAL, categoryId = null), RequiredAction.INFORMATION_COMPLETION, completable),
            Case("retryable legacy no reason", itemFixture(analysis = AnalysisStatus.FAILED_RETRYABLE, categoryId = null), RequiredAction.INFORMATION_COMPLETION, retryable),
            Case("terminal legacy no reason", itemFixture(analysis = AnalysisStatus.FAILED_TERMINAL, categoryId = null), RequiredAction.INFORMATION_COMPLETION, completable),
            Case("EXTRACTION_UNRESOLVED precedes pending review", itemFixture(analysis = AnalysisStatus.PARTIAL, categoryId = null, missingReason = CategoryMissingReason.EXTRACTION_UNRESOLVED, review = ReviewStatus.PENDING), RequiredAction.INFORMATION_COMPLETION, completable),
            Case("blank name precedes category assignment and review", itemFixture(name = "  \n", categoryId = null, missingReason = CategoryMissingReason.AI_ABSTAINED, review = ReviewStatus.PENDING), RequiredAction.INFORMATION_COMPLETION, editable),
            Case("null name precedes category reassignment", itemFixture(name = null, categoryId = null, missingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED), RequiredAction.INFORMATION_COMPLETION, editable),
            Case("empty name", itemFixture(name = ""), RequiredAction.INFORMATION_COMPLETION, editable),
            Case("ready pending review", itemFixture(review = ReviewStatus.PENDING), RequiredAction.CLASSIFICATION_REVIEW, setOf(ItemAction.EDIT, ItemAction.DELETE, ItemAction.REVIEW)),
            Case("confirmed", itemFixture(review = ReviewStatus.CONFIRMED), RequiredAction.NONE, editable),
            Case("deferred not resurfaced", itemFixture(review = ReviewStatus.DEFERRED), RequiredAction.NONE, editable),
            Case("not required", itemFixture(review = ReviewStatus.NOT_REQUIRED), RequiredAction.NONE, editable),
            Case("retryable retained metadata", itemFixture(analysis = AnalysisStatus.FAILED_RETRYABLE), RequiredAction.NONE, retryable),
            Case("partial manually completed then category deleted", itemFixture(analysis = AnalysisStatus.PARTIAL, categoryId = null, missingReason = CategoryMissingReason.CUSTOM_CATEGORY_DELETED, completed = fixtureTime), RequiredAction.CATEGORY_REASSIGNMENT, editable),
        )
        for (case in cases) {
            val actual = evaluateItem(case.item)
            assertEquals(case.required, actual.requiredAction, case.label)
            assertEquals(case.actions, actual.allowedActions, case.label)
        }
    }

    @Test
    fun nonactive_overrides_every_analysis_and_review_state() {
        for (lifecycle in listOf(LifecycleStatus.ARCHIVED, LifecycleStatus.DELETED)) {
            for (analysis in AnalysisStatus.entries) for (review in ReviewStatus.entries) {
                assertEquals(ItemPolicy(RequiredAction.NONE, emptySet()), evaluateItem(itemFixture(analysis = analysis, review = review, lifecycle = lifecycle, name = null, categoryId = null)))
            }
        }
    }

    @Test
    fun manual_completion_preserves_diagnostic_analysis_and_suppresses_completion_retry_and_review() {
        for (analysis in listOf(AnalysisStatus.PARTIAL, AnalysisStatus.FAILED_RETRYABLE, AnalysisStatus.FAILED_TERMINAL, AnalysisStatus.READY)) {
            val item = itemFixture(analysis = analysis, completed = fixtureTime, review = ReviewStatus.PENDING)
            assertEquals(ItemPolicy(RequiredAction.CLASSIFICATION_REVIEW, setOf(ItemAction.EDIT, ItemAction.DELETE)), evaluateItem(item))
            assertEquals(analysis, item.analysis.status)
        }
    }

    @Test
    fun server_actions_and_required_action_are_stored_without_fake_recalculation() {
        // Deliberately differ from Fake policy for otherwise READY complete metadata.
        val item = itemFixture(required = RequiredAction.INFORMATION_COMPLETION, actions = setOf(ItemAction.DELETE))
        assertEquals(RequiredAction.INFORMATION_COMPLETION, item.requiredAction)
        assertEquals(setOf(ItemAction.DELETE), item.allowedActions)
        assertEquals(setOf(ItemAction.DELETE), sanitizeAllowedActions(item.analysis.status, item.requiredAction, item.allowedActions))
        assertEquals(RequiredAction.NONE, evaluateItem(item).requiredAction)
        assertEquals(RequiredAction.INFORMATION_COMPLETION, item.requiredAction)
    }

    @Test
    fun descriptive_unknowns_preserve_server_known_actions() {
        val actions = setOf(ItemAction.EDIT, ItemAction.REVIEW, ItemAction.DELETE)
        val item = itemFixture(review = ReviewStatus.UNKNOWN, required = RequiredAction.CLASSIFICATION_REVIEW, actions = actions).copy(
            product = ProductSnapshot(name = "상품", nameSource = ValueSource.UNKNOWN, imageSource = ValueSource.UNKNOWN),
            category = ItemCategory(source = ValueSource.UNKNOWN, missingReason = CategoryMissingReason.UNKNOWN),
            purpose = ItemPurpose(null, ValueSource.UNKNOWN),
        )
        assertEquals(actions, item.allowedActions)
        assertEquals(actions, sanitizeAllowedActions(item.analysis.status, item.requiredAction, item.allowedActions))
        assertEquals(HomeActionGroup.CLASSIFICATION_REVIEW, homeActionGroup(item.requiredAction))
    }

    @Test
    fun branch_unknown_only_preserves_delete_when_server_allowed_it() {
        for ((analysis, required) in listOf(
            AnalysisStatus.UNKNOWN to RequiredAction.NONE,
            AnalysisStatus.READY to RequiredAction.UNKNOWN,
            AnalysisStatus.UNKNOWN to RequiredAction.UNKNOWN,
        )) {
            val item = itemFixture(analysis = analysis, required = required, actions = setOf(ItemAction.EDIT, ItemAction.DELETE))
            assertEquals(setOf(ItemAction.DELETE), sanitizeAllowedActions(item.analysis.status, item.requiredAction, item.allowedActions))
            assertEquals(emptySet(), sanitizeAllowedActions(item.analysis.status, item.requiredAction, setOf(ItemAction.EDIT)))
            assertFalse(isListEligible(item))
        }
    }

    @Test
    fun fake_does_not_invent_delete_for_unknown_branch() {
        assertEquals(ItemPolicy(RequiredAction.UNKNOWN, emptySet()), evaluateItem(itemFixture(analysis = AnalysisStatus.UNKNOWN, actions = setOf(ItemAction.EDIT))))
        assertEquals(ItemPolicy(RequiredAction.UNKNOWN, setOf(ItemAction.DELETE)), evaluateItem(itemFixture(required = RequiredAction.UNKNOWN)))
    }

    @Test
    fun home_projection_uses_an_independent_required_action_table() {
        val table = listOf(
            RequiredAction.ANALYSIS_IN_PROGRESS to HomeActionGroup.ANALYSIS_IN_PROGRESS,
            RequiredAction.INFORMATION_COMPLETION to HomeActionGroup.INFORMATION_COMPLETION,
            RequiredAction.CATEGORY_ASSIGNMENT to HomeActionGroup.INFORMATION_COMPLETION,
            RequiredAction.CATEGORY_REASSIGNMENT to HomeActionGroup.INFORMATION_COMPLETION,
            RequiredAction.CLASSIFICATION_REVIEW to HomeActionGroup.CLASSIFICATION_REVIEW,
            RequiredAction.NONE to null,
            RequiredAction.UNKNOWN to null,
        )
        for ((action, group) in table) assertEquals(group, homeActionGroup(action), action.name)
    }

    @Test
    fun list_eligibility_uses_active_minimum_information_not_ready_status_or_descriptive_enums() {
        for (analysis in AnalysisStatus.entries.filter { it != AnalysisStatus.UNKNOWN }) assertTrue(isListEligible(itemFixture(analysis = analysis)))
        assertTrue(isListEligible(itemFixture(analysis = AnalysisStatus.PARTIAL, review = ReviewStatus.UNKNOWN).copy(category = ItemCategory("C026", ValueSource.UNKNOWN))))
        for (name in listOf(null, "", "  \n")) assertFalse(isListEligible(itemFixture(name = name)))
        for (id in listOf(null, "", "  ")) assertFalse(isListEligible(itemFixture(categoryId = id)))
        for (lifecycle in listOf(LifecycleStatus.ARCHIVED, LifecycleStatus.DELETED)) assertFalse(isListEligible(itemFixture(lifecycle = lifecycle)))
    }
}
