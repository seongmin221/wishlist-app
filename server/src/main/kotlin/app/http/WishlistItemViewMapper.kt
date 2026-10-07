package app.http

import app.wishlist.*

object WishlistItemViewMapper {
    fun map(item: WishlistItem): WishlistItemDto {
        val stored = item.storedState
        val state = stored.state
        val policy = WishlistItemPolicy.evaluate(state)
        return WishlistItemDto(
            id = item.id.toString(), clientSubmissionId = item.clientSubmissionId.toString(),
            version = item.version, sourceUrl = item.sourceUrl,
            product = ProductDto(name = state.productName, imageUrl = item.productImageUrl,
                nameSource = stored.nameSource, imageSource = stored.imageSource),
            category = CategoryDto(id = state.categoryId, source = stored.categorySource, missingReason = state.categoryMissingReason,
                name=item.categoryName,parentId=item.categoryParentId,kind=item.categoryKind),
            purpose = PurposeDto(id = stored.purposeId, name = item.purposeName, colorKey = item.purposeColorKey,
                iconKey = item.purposeIconKey, source = stored.purposeSource),
            analysis = AnalysisDto(status = state.analysisStatus, failureCode = publicFailureCode(state.analysisStatus, item.analysisFailureCode)),
            reviewStatus = state.reviewStatus, lifecycleStatus = state.lifecycleStatus,
            requiredAction = policy.requiredAction, allowedActions = policy.allowedActions,
            manualCompletionAt = state.manualCompletionAt?.toString(), clientCreatedAt = item.clientCreatedAt?.toString(),
            createdAt = item.createdAt.toString(), updatedAt = item.updatedAt.toString(),
        )
    }

    private fun publicFailureCode(status: AnalysisStatus, code: String?): AnalysisFailureCode? {
        val known = AnalysisFailureCode.fromStored(code)
        return when {
            status == AnalysisStatus.PROCESSING || status == AnalysisStatus.READY -> null
            known != null -> known
            status == AnalysisStatus.FAILED_RETRYABLE -> AnalysisFailureCode.ANALYSIS_RETRYABLE_FAILURE
            status == AnalysisStatus.FAILED_TERMINAL || code != null -> AnalysisFailureCode.ANALYSIS_FAILED
            else -> null
        }
    }
}
