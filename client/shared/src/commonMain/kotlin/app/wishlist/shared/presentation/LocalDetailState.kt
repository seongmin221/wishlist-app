package app.wishlist.shared.presentation

import app.wishlist.shared.domain.RelativeTime

/** The local link the detail screen shows; [savedAt] is relative to the moment the state was computed. */
data class LocalDetailRow(
    val submissionId: String,
    val host: String,
    val sourceUrl: String,
    val savedAt: RelativeTime,
    val status: RowStatus,
)

/** How the local detail screen ends; once set it never changes. */
sealed interface LocalDetailOutcome {
    /** The link was accepted: the shell replaces this screen with the item's detail. */
    data class MovedTo(val itemId: String) : LocalDetailOutcome

    /** The user deleted the link here. */
    data object Deleted : LocalDetailOutcome

    /** D19: the server accepted it as already DELETED, so nothing was cached. */
    data object RemovedOnServer : LocalDetailOutcome

    /** The link is gone and why is unknown (the lookup failed, or it vanished while signed out). */
    data object Gone : LocalDetailOutcome
}

/**
 * Local (not yet sent) link detail (C4). [row] is null before the first view (store not ready) and
 * keeps the last shown row once it left the view. [deleteFailed] is set by a failed delete and
 * cleared by the next view; the platform shows "지우지 못했어요" briefly.
 */
data class LocalDetailState(
    val row: LocalDetailRow?,
    val canDelete: Boolean,
    val deleting: Boolean,
    val deleteFailed: Boolean,
    val outcome: LocalDetailOutcome?,
) {
    companion object {
        val Initial = LocalDetailState(null, canDelete = false, deleting = false, deleteFailed = false, outcome = null)
    }
}
