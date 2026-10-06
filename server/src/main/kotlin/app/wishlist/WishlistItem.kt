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
) {
    val id: UUID get() = storedState.id
    val version: Int get() = storedState.version
}
