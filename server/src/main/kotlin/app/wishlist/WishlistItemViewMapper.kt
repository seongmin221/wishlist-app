package app.wishlist

import app.http.AnalysisDto
import app.http.CategoryDto
import app.http.ProductDto
import app.http.PurposeDto
import app.http.WishlistItemDto

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
            category = CategoryDto(id = state.categoryId, source = stored.categorySource, missingReason = state.categoryMissingReason),
            purpose = PurposeDto(id = stored.purposeId, source = stored.purposeSource),
            analysis = AnalysisDto(status = state.analysisStatus, failureCode = publicFailureCode(state.analysisStatus, item.analysisFailureCode)),
            reviewStatus = state.reviewStatus, lifecycleStatus = state.lifecycleStatus,
            requiredAction = policy.requiredAction, allowedActions = policy.allowedActions,
            manualCompletionAt = state.manualCompletionAt?.toString(), clientCreatedAt = item.clientCreatedAt?.toString(),
            createdAt = item.createdAt.toString(), updatedAt = item.updatedAt.toString(),
        )
    }

    private val publicFailureCodes = setOf(
        "BLOCKED_ADDRESS", "UNSUPPORTED_CONTENT", "ACCESS_DENIED", "AI_ABSTAINED", "AI_UNUSABLE_RESPONSE",
        "AI_INVALID_CANDIDATE", "AI_USAGE_OUT_OF_RANGE", "AI_BUDGET_EXCEEDED", "AI_CONFIGURATION_ERROR",
    )

    private fun publicFailureCode(status: AnalysisStatus, code: String?): String? = when {
        status == AnalysisStatus.PROCESSING || status == AnalysisStatus.READY -> null
        code in publicFailureCodes -> code
        status == AnalysisStatus.FAILED_RETRYABLE -> "ANALYSIS_RETRYABLE_FAILURE"
        status == AnalysisStatus.FAILED_TERMINAL || code != null -> "ANALYSIS_FAILED"
        else -> null
    }
}
