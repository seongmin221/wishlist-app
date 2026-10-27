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
    /** READY user image linked to the item; display URLs are signed outside the read transaction. */
    val mediaId: UUID? = null,
    val mediaObjectKey: String? = null,
    /** Only single-item responses carry this; list cards leave it null so the JSON key is absent. */
    val deletionImpact: DeletionImpact? = null,
    /** Visible OPEN duplicate pairs where this item is the new side; null when there are none. */
    val duplicateCounts: app.duplicates.DuplicateCounts? = null,
) {
    val id: UUID get() = storedState.id
    val version: Int get() = storedState.version
}
