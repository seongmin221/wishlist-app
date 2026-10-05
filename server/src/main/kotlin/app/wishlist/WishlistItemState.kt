package app.wishlist

import java.time.Instant

enum class AnalysisStatus { PROCESSING, READY, PARTIAL, FAILED_RETRYABLE, FAILED_TERMINAL }
enum class ReviewStatus { NOT_REQUIRED, PENDING, CONFIRMED, DEFERRED }
enum class LifecycleStatus { ACTIVE, ARCHIVED, DELETED }
enum class CategoryMissingReason { EXTRACTION_UNRESOLVED, AI_ABSTAINED, AI_RESPONSE_UNUSABLE, CUSTOM_CATEGORY_DELETED }
enum class ValueSource { AI, USER, UNASSIGNED }
enum class RequiredAction {
    ANALYSIS_IN_PROGRESS, INFORMATION_COMPLETION, CATEGORY_ASSIGNMENT, CATEGORY_REASSIGNMENT, CLASSIFICATION_REVIEW, NONE,
}
enum class HomeActionGroup { ANALYSIS_IN_PROGRESS, INFORMATION_COMPLETION, CLASSIFICATION_REVIEW }
enum class ItemAction { EDIT, DELETE, REVIEW, MANUAL_COMPLETE, REANALYZE }

data class WishlistItemState(
    val analysisStatus: AnalysisStatus,
    val reviewStatus: ReviewStatus,
    val lifecycleStatus: LifecycleStatus,
    val productName: String?,
    val categoryId: String?,
    val categoryMissingReason: CategoryMissingReason?,
    val manualCompletionAt: Instant?,
)

data class ItemPolicy(
    val requiredAction: RequiredAction,
    val homeActionGroup: HomeActionGroup?,
    val allowedActions: Set<ItemAction>,
)
