package app.wishlist

object WishlistItemPolicy {
    fun evaluate(state: WishlistItemState): ItemPolicy {
        if (state.lifecycleStatus != LifecycleStatus.ACTIVE) {
            return ItemPolicy(RequiredAction.NONE, null, emptySet())
        }
        if (state.analysisStatus == AnalysisStatus.PROCESSING) {
            return ItemPolicy(
                RequiredAction.ANALYSIS_IN_PROGRESS, HomeActionGroup.ANALYSIS_IN_PROGRESS, setOf(ItemAction.DELETE),
            )
        }

        val required = when {
            state.productName.isNullOrBlank() -> RequiredAction.INFORMATION_COMPLETION
            state.categoryMissingReason == CategoryMissingReason.EXTRACTION_UNRESOLVED -> RequiredAction.INFORMATION_COMPLETION
            state.categoryMissingReason in setOf(CategoryMissingReason.AI_ABSTAINED, CategoryMissingReason.AI_RESPONSE_UNUSABLE) -> RequiredAction.CATEGORY_ASSIGNMENT
            state.categoryMissingReason == CategoryMissingReason.CUSTOM_CATEGORY_DELETED -> RequiredAction.CATEGORY_REASSIGNMENT
            state.categoryId.isNullOrBlank() -> RequiredAction.INFORMATION_COMPLETION
            state.reviewStatus == ReviewStatus.PENDING -> RequiredAction.CLASSIFICATION_REVIEW
            else -> RequiredAction.NONE
        }
        val group = when (required) {
            RequiredAction.ANALYSIS_IN_PROGRESS -> HomeActionGroup.ANALYSIS_IN_PROGRESS
            RequiredAction.INFORMATION_COMPLETION, RequiredAction.CATEGORY_ASSIGNMENT, RequiredAction.CATEGORY_REASSIGNMENT -> HomeActionGroup.INFORMATION_COMPLETION
            RequiredAction.CLASSIFICATION_REVIEW -> HomeActionGroup.CLASSIFICATION_REVIEW
            RequiredAction.NONE -> null
        }
        val actions = buildSet {
            add(ItemAction.EDIT)
            add(ItemAction.DELETE)
            if (state.manualCompletionAt == null) {
                if (state.analysisStatus in setOf(AnalysisStatus.PARTIAL, AnalysisStatus.FAILED_RETRYABLE, AnalysisStatus.FAILED_TERMINAL)) {
                    add(ItemAction.MANUAL_COMPLETE)
                }
                if (state.analysisStatus == AnalysisStatus.FAILED_RETRYABLE) add(ItemAction.REANALYZE)
                if (state.analysisStatus == AnalysisStatus.READY && required == RequiredAction.CLASSIFICATION_REVIEW) {
                    add(ItemAction.REVIEW)
                }
            }
        }
        return ItemPolicy(required, group, actions)
    }
}
