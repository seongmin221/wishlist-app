package app.wishlist.shared.model

import kotlin.time.Instant

/** Server-shaped snapshot. Callers store requiredAction/allowedActions without local reevaluation. */
data class WishlistItem(
    val id: String,
    val clientSubmissionId: String,
    val version: Int,
    val sourceUrl: String,
    val product: ProductSnapshot,
    val category: ItemCategory,
    val purpose: ItemPurpose,
    val analysis: ItemAnalysis,
    val reviewStatus: ReviewStatus,
    val lifecycleStatus: LifecycleStatus,
    val requiredAction: RequiredAction,
    val createdAt: Instant,
    val updatedAt: Instant,
    val manualCompletionAt: Instant? = null,
    val allowedActions: Set<ItemAction> = emptySet(),
    val clientCreatedAt: Instant? = null,
) {
    init { require(version > 0) { "Item version must be positive" } }

    val createdAtIso: String get() = createdAt.toString()
    val updatedAtIso: String get() = updatedAt.toString()
    val manualCompletionAtIso: String? get() = manualCompletionAt?.toString()
    val clientCreatedAtIso: String? get() = clientCreatedAt?.toString()

    /** When the user saved it: the shared time ([clientCreatedAt]), else the server creation time. */
    val savedAt: Instant get() = clientCreatedAt ?: createdAt
}

data class ProductSnapshot(
    val name: String? = null,
    val imageUrl: String? = null,
    val price: DecimalAmount? = null,
    val currency: String? = null,
    val brand: String? = null,
    val merchant: String? = null,
    val metadataCheckedAt: Instant? = null,
    val nameSource: ValueSource? = null,
    val imageSource: ValueSource? = null,
) {
    val metadataCheckedAtIso: String? get() = metadataCheckedAt?.toString()
}

data class ItemCategory(
    val id: String? = null,
    val source: ValueSource? = null,
    val missingReason: CategoryMissingReason? = null,
    val name: String? = null,
    val parentId: String? = null,
    val kind: String? = null,
) {
    init { require(id == null || missingReason == null) { "Assigned category cannot have a missing reason" } }
}

/** [name], [colorKey] and [iconKey] are server wire values kept verbatim (e.g. `CORAL`, not lowercased). */
data class ItemPurpose(
    val id: String? = null,
    val source: ValueSource = ValueSource.UNASSIGNED,
    val name: String? = null,
    val colorKey: String? = null,
    val iconKey: String? = null,
)

data class ItemAnalysis(val status: AnalysisStatus, val failureCode: String? = null)
