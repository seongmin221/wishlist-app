package app.wishlist.shared.presentation

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomePreview
import app.wishlist.shared.model.PurposeSummary

/**
 * What a home row tells the user; the platform maps each value to its own wording. A queued row says
 * why it waits: no connection yet ([WAITING_NETWORK]), a server-side failure or a 429 the app resends
 * by itself ([RETRYING]), or a sign-in the server refused ([NEEDS_SIGN_IN]).
 */
enum class RowStatus { LOCAL_ONLY, SENDING, WAITING_NETWORK, RETRYING, NEEDS_SIGN_IN, FAILED, PROCESSING }

/** Where tapping a row goes: a link still on this device, or a server item. */
sealed interface HomeRowTarget {
    data class Local(val submissionId: String) : HomeRowTarget
    data class Item(val itemId: String) : HomeRowTarget
}

/**
 * One saved link on the home list. [key] is stable per row and unique within a state, for list
 * diffing; [savedAt] is relative to the moment the state was computed.
 */
data class HomeRow(
    val key: String,
    val target: HomeRowTarget,
    val host: String,
    val sourceUrl: String,
    val savedAt: RelativeTime,
    val status: RowStatus,
)

/** One HOME-01 action group with items: the exact [count] and at most four newest [previews]. */
data class HomeGroupCard(val group: HomeActionGroup, val count: Int, val previews: List<HomePreview>)

/** Home screen state (C3, C7). Platform UI state (expanded rows, dialogs) never lives here. */
sealed interface HomeState {
    /** Login restore or the signed-in account's first local view is not available yet. */
    data object Loading : HomeState

    /** Signed out: links kept on this device, oldest first. */
    data class LoggedOut(val pending: List<HomeRow>) : HomeState

    /**
     * Signed in. [pending] is this account's links not yet sent from this device (the "분석 대기"
     * card), in the view's order; server-side items come only from HOME-01. [groups] holds the
     * groups with items, in board order (review, completion, analysis); [purposes] is HOME-01's
     * recent purposes as sent. [todoCount] = pending + the three group counts.
     *
     * Before the first HOME-01 answer [summaryLoaded] is false: [summaryError] is set when that first
     * load failed (retry instead of the todo area). A later failure keeps the last summary and sets
     * [refreshFailed]. [refreshing] is the pull indicator (coordinator run and HOME-01 both done).
     */
    data class LoggedIn(
        val pending: List<HomeRow>,
        val groups: List<HomeGroupCard> = emptyList(),
        val purposes: List<PurposeSummary> = emptyList(),
        val todoCount: Int = pending.size,
        val summaryLoaded: Boolean = false,
        val summaryError: ClientError? = null,
        val refreshing: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : HomeState
}
