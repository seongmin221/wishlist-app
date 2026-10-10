package app.wishlist

import java.time.Instant
import java.util.UUID

data class WishlistItem(
    val storedState: StoredWishlistItemState,
    val clientSubmissionId: UUID,
    val sourceUrl: String,
    val productImageUrl: String?,
    val analysisFailureCode: String?,
    val clientCreatedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val categoryName:String? = null,
    val categoryParentId:String? = null,
    val categoryKind:String? = null,
    val purposeName: String? = null,
    val purposeColorKey: String? = null,
    val purposeIconKey: String? = null,
    val brand: String? = null,
    val price: java.math.BigDecimal? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val metadataCheckedAt: Instant? = null,
) {
    val id: UUID get() = storedState.id
    val version: Int get() = storedState.version
}
