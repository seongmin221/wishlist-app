package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.WishlistItem
import kotlin.time.Instant

/** Original URL identity is preserved, including whitespace; no normalization occurs here. */
data class CreateItemCommand(
    val submissionId: String,
    val sourceUrl: String,
    val clientCreatedAt: Instant?,
)

/** ITEM-01: a local submission retains the same UUID key across retries. */
interface CreateItemRepository {
    suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem>
}

/** ITEM-03: owner-scoped lookup; deleted items are NOT_FOUND. */
interface GetItemRepository {
    suspend fun get(id: String): ClientResult<WishlistItem>
}
