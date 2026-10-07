package app.wishlist.shared.submission

import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.WishlistItem

/**
 * The card shown right after an Android share, decided by the state at that moment (C3-D9). The iOS
 * extension does not link Shared and has its own Swift enum (with SAVED_OPEN_APP).
 */
enum class ShareCardKind { SAVED, LOCAL, OFFLINE, INVALID, STORE_FAILED }

/** Why a flush or refresh was asked for. Every trigger runs the same single-flight flush. */
enum class FlushTrigger { LAUNCH, FOREGROUND, NETWORK_RESTORED, SIGNED_IN, USER_REFRESH, SHARE_RECEIVED }

/** One iOS app-group inbox file (`inbox/<key>.json`), as written by the share extension. */
data class InboxRecord(
    val clientSubmissionId: String,
    val sourceUrl: String,
    val sharedAtIso: String,
    val accountBinding: String?,
)

/** deletable: keys whose files may be removed (imported, or permanently invalid). retained: store failure, retry next time. */
data class InboxImportResult(val deletable: List<String>, val retained: List<String>)

/**
 * What the home screen shows for the current session. [local] is the store's pending() view (that
 * account's queue plus unbound rows; unbound rows only when signed out); [processing] is the
 * account's cached items still being analysed, oldest first. Both are empty for another account.
 */
data class SubmissionView(
    val accountId: String?,
    val local: List<LocalSubmission>,
    val processing: List<WishlistItem>,
    val flushing: Boolean,
)
