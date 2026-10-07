package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.WishlistItem

/**
 * Per-account local persistence. Every operation is committed through the AuthSession gate, so a
 * stale snapshot is rejected with SESSION_CHANGED and DB failures surface as
 * UNAVAILABLE/LOCAL_STORE_FAILURE. Cache clearing/removal never touches pending submissions.
 */
interface LocalStore {
    suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit>
    suspend fun pending(): ClientResult<List<LocalSubmission>>
    suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit>
    suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?>
    suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem): ClientResult<Unit>
    suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int): ClientResult<Unit>
    suspend fun clearCurrentCache(): ClientResult<Unit>
}
