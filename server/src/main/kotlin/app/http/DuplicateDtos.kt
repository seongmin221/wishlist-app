package app.http

import kotlinx.serialization.Serializable

@Serializable
data class DuplicatePageDto(
    val item: WishlistItemDto,
    val candidates: List<DuplicateCandidateDto>,
    val totalCount: Long,
    val nextCursor: String?,
)

@Serializable
data class DuplicateCandidateDto(
    val id: String,
    val kind: String,
    val bases: List<String>,
    val version: Int,
    val allowedDecisions: List<String>,
    val existingItem: WishlistItemDto,
)

@Serializable
data class DuplicateDecisionDto(
    val candidate: DecidedCandidateDto,
    val deletedItemId: String?,
    val remainingItemIds: List<String>,
    val purposes: List<MovedPurposeDto>,
)

@Serializable
data class DecidedCandidateDto(val id: String, val decision: String, val decidedAt: String, val version: Int)
