package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import kotlin.time.Instant

/**
 * Per-account local persistence. Every account-scoped operation is committed through the
 * AuthSession gate, so a stale snapshot is rejected with SESSION_CHANGED and DB failures surface as
 * UNAVAILABLE/LOCAL_STORE_FAILURE. Cache clearing/removal never touches pending submissions.
 * Submission rows whose binding differs from the snapshot account are never changed
 * (VALIDATION/ACCOUNT_BINDING_MISMATCH).
 */
interface LocalStore {
    /**
     * Saves against the current session; the binding must be null or the current account. Same
     * key + same URL keeps the existing row (no-op Success). Same key + different URL is
     * CONFLICT/SUBMISSION_KEY_REUSED.
     */
    suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit>

    /** iOS inbox import: keeps the share-time binding as given (may differ from the current account). Same key guard as [saveSubmission]. */
    suspend fun importSubmission(submission: LocalSubmission): ClientResult<Unit>

    /** Logged out: unbound rows only. Logged in: that account's rows plus unbound ones until bound. Ordered by (sharedAt µs, key). */
    suspend fun pending(): ClientResult<List<LocalSubmission>>

    /** One transaction: SUBMITTING → PENDING, unbound → the snapshot account; returns that account's whole queue. */
    suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>>

    /** Records a send outcome on a row bound to the snapshot account (NOT_FOUND/SUBMISSION_NOT_FOUND if absent). */
    suspend fun markSubmission(
        snapshot: SessionSnapshot,
        id: String,
        status: SubmissionStatus,
        error: ClientError?,
        retryAfter: Instant?,
    ): ClientResult<Unit>

    /** The snapshot account's cached ACTIVE items whose analysis is still PROCESSING. */
    suspend fun processingItems(snapshot: SessionSnapshot): ClientResult<List<WishlistItem>>

    suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit>
    suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?>

    /**
     * Atomically writes the accepted item to the cache and deletes its submission. The item must
     * belong to [submissionId] (UUIDs compared case-insensitively), otherwise
     * VALIDATION/SUBMISSION_ITEM_MISMATCH and nothing is written.
     */
    suspend fun accept(snapshot: SessionSnapshot, submissionId: String, item: WishlistItem): ClientResult<Unit>
    suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String, throughVersion: Int): ClientResult<Unit>
    suspend fun clearCurrentCache(): ClientResult<Unit>

    /** Device-level key/value state (not account-scoped). */
    suspend fun readAppState(key: String): ClientResult<String?>

    /** A null [value] deletes the key. */
    suspend fun writeAppState(key: String, value: String?): ClientResult<Unit>
}
