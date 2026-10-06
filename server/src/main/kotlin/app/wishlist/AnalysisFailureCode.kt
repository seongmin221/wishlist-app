package app.wishlist

import kotlinx.serialization.Serializable

/** Current failure vocabulary. Persisted unknown legacy diagnostics are parsed only at read boundaries. */
@Serializable
enum class AnalysisFailureCode(val isPublic: Boolean, val categoryMissingReason: CategoryMissingReason = CategoryMissingReason.EXTRACTION_UNRESOLVED) {
    BLOCKED_ADDRESS(true),
    UNSUPPORTED_CONTENT(true),
    ACCESS_DENIED(true),
    AI_ABSTAINED(true, CategoryMissingReason.AI_ABSTAINED),
    AI_UNUSABLE_RESPONSE(true, CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_INVALID_CANDIDATE(true, CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_USAGE_OUT_OF_RANGE(true, CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_BUDGET_EXCEEDED(true),
    AI_CONFIGURATION_ERROR(true),
    ANALYSIS_RETRYABLE_FAILURE(true),
    ANALYSIS_FAILED(true);

    companion object {
        private val byName = entries.associateBy { it.name }
        fun fromStored(value: String?): AnalysisFailureCode? = byName[value]
    }
}
