package app.wishlist.shared.domain

import app.wishlist.shared.model.*

data class ItemPolicy(val requiredAction: RequiredAction, val allowedActions: Set<ItemAction>)

/**
 * Reproduces server policy for Fake only. Remote stores the server policy directly.
 * Reads state axes only; the item's stored requiredAction/allowedActions never feed back in.
 */
fun evaluateItem(item: WishlistItem): ItemPolicy {
    if (item.lifecycleStatus != LifecycleStatus.ACTIVE) return ItemPolicy(RequiredAction.NONE, emptySet())
    // Without a server-supplied action set there is no DELETE to keep.
    if (item.analysis.status == AnalysisStatus.UNKNOWN) return ItemPolicy(RequiredAction.UNKNOWN, emptySet())
    if (item.analysis.status == AnalysisStatus.PROCESSING) {
        return ItemPolicy(RequiredAction.ANALYSIS_IN_PROGRESS, setOf(ItemAction.DELETE))
    }

    val required = when {
        item.product.name.isNullOrBlank() -> RequiredAction.INFORMATION_COMPLETION
        item.category.missingReason == CategoryMissingReason.EXTRACTION_UNRESOLVED -> RequiredAction.INFORMATION_COMPLETION
        item.category.missingReason == CategoryMissingReason.AI_ABSTAINED ||
            item.category.missingReason == CategoryMissingReason.AI_RESPONSE_UNUSABLE -> RequiredAction.CATEGORY_ASSIGNMENT
        item.category.missingReason == CategoryMissingReason.CUSTOM_CATEGORY_DELETED -> RequiredAction.CATEGORY_REASSIGNMENT
        item.category.id.isNullOrBlank() -> RequiredAction.INFORMATION_COMPLETION
        item.reviewStatus == ReviewStatus.PENDING -> RequiredAction.CLASSIFICATION_REVIEW
        else -> RequiredAction.NONE
    }
    val actions = buildSet {
        add(ItemAction.EDIT)
        add(ItemAction.DELETE)
        if (item.manualCompletionAt == null) {
            when (item.analysis.status) {
                AnalysisStatus.PARTIAL, AnalysisStatus.FAILED_TERMINAL -> add(ItemAction.MANUAL_COMPLETE)
                AnalysisStatus.FAILED_RETRYABLE -> {
                    add(ItemAction.MANUAL_COMPLETE)
                    add(ItemAction.REANALYZE)
                }
                AnalysisStatus.READY -> if (required == RequiredAction.CLASSIFICATION_REVIEW) add(ItemAction.REVIEW)
            }
        }
    }
    return ItemPolicy(required, actions)
}

/** Projects the server requiredAction for home presentation; does not evaluate item policy. */
fun homeActionGroup(action: RequiredAction): HomeActionGroup? = when (action) {
    RequiredAction.ANALYSIS_IN_PROGRESS -> HomeActionGroup.ANALYSIS_IN_PROGRESS
    RequiredAction.INFORMATION_COMPLETION, RequiredAction.CATEGORY_ASSIGNMENT,
    RequiredAction.CATEGORY_REASSIGNMENT -> HomeActionGroup.INFORMATION_COMPLETION
    RequiredAction.CLASSIFICATION_REVIEW -> HomeActionGroup.CLASSIFICATION_REVIEW
    RequiredAction.NONE, RequiredAction.UNKNOWN -> null
}

fun isListEligible(item: WishlistItem): Boolean =
    item.lifecycleStatus == LifecycleStatus.ACTIVE &&
        item.analysis.status != AnalysisStatus.UNKNOWN && item.requiredAction != RequiredAction.UNKNOWN &&
        !item.product.name.isNullOrBlank() && !item.category.id.isNullOrBlank()

/** Remote mapping boundary safety: keep supplied actions, restricting unknown branches to supplied DELETE. */
fun sanitizeAllowedActions(
    analysisStatus: AnalysisStatus,
    requiredAction: RequiredAction,
    actions: Set<ItemAction>,
): Set<ItemAction> = if (analysisStatus == AnalysisStatus.UNKNOWN || requiredAction == RequiredAction.UNKNOWN) {
    actions.filterTo(mutableSetOf()) { it == ItemAction.DELETE }
} else {
    actions
}
