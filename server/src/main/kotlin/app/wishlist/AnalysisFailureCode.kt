package app.wishlist

import kotlinx.serialization.Serializable

/**
 * Current failure vocabulary; every entry is part of the public API contract.
 * Persisted unknown legacy diagnostics are parsed only at read boundaries.
 */
@Serializable
enum class AnalysisFailureCode(val categoryMissingReason: CategoryMissingReason = CategoryMissingReason.EXTRACTION_UNRESOLVED) {
    BLOCKED_ADDRESS,
    UNSUPPORTED_CONTENT,
    ACCESS_DENIED,
    AI_ABSTAINED(CategoryMissingReason.AI_ABSTAINED),
    AI_UNUSABLE_RESPONSE(CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_INVALID_CANDIDATE(CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_USAGE_OUT_OF_RANGE(CategoryMissingReason.AI_RESPONSE_UNUSABLE),
    AI_BUDGET_EXCEEDED,
    AI_CONFIGURATION_ERROR,
    ANALYSIS_RETRYABLE_FAILURE,
    ANALYSIS_FAILED;

    companion object {
        private val byName = entries.associateBy { it.name }
        fun fromStored(value: String?): AnalysisFailureCode? = byName[value]
    }
}
