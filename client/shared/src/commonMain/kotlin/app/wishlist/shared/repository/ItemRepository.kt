package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
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

/**
 * ITEM-01 for one session snapshot (Kotlin-internal; the public facade stays [CreateItemRepository]).
 * The request is made for [expected], never for whatever session is current when it runs: if the
 * session is no longer [expected], the result is SESSION_CHANGED and nothing is sent or stored.
 */
internal interface SnapshotCreateItemRepository : CreateItemRepository {
    suspend fun create(command: CreateItemCommand, expected: SessionSnapshot): ClientResult<WishlistItem>
}

/** ITEM-03: owner-scoped lookup; deleted items are NOT_FOUND. */
interface GetItemRepository {
    suspend fun get(id: String): ClientResult<WishlistItem>
}
