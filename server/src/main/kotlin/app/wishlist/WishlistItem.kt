package app.wishlist

import java.time.Instant
import java.util.UUID

data class WishlistItem(
    val id: UUID,
    val clientSubmissionId: UUID,
    val sourceUrl: String,
    val version: Int,
    val analysisStatus: String,
    val lifecycleStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
