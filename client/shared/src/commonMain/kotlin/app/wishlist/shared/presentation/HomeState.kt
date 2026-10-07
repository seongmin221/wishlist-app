package app.wishlist.shared.presentation

import app.wishlist.shared.domain.RelativeTime

/** What a home row tells the user; the platform maps each value to its own wording. */
enum class RowStatus { LOCAL_ONLY, SENDING, WAITING_NETWORK, FAILED, PROCESSING }

/**
 * One saved link on the home list. [key] is stable per row and unique within a state, for list
 * diffing; [savedAt] is relative to the moment the state was computed.
 */
data class HomeRow(
    val key: String,
    val host: String,
    val sourceUrl: String,
    val savedAt: RelativeTime,
    val status: RowStatus,
)

/** Home screen state (C3). Platform UI state (expanded rows, dialogs) never lives here. */
sealed interface HomeState {
    /** Login restore or the signed-in account's first local view is not available yet. */
    data object Loading : HomeState

    /** Signed out: links kept on this device, oldest first. */
    data class LoggedOut(val pending: List<HomeRow>) : HomeState

    /** Signed in: local sends oldest first, then server-side processing items oldest first. */
    data class LoggedIn(val processing: List<HomeRow>, val refreshing: Boolean) : HomeState
}
