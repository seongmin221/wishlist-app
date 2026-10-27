package app.wishlist.shared.presentation

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.WishlistItem

/**
 * One card of the continuous-processing queue. [anchorCursor] is the card's HOME-02 anchor token; a
 * card without one still shows, it just cannot anchor a foreground window.
 */
data class HomeFlowCard(val item: WishlistItem, val anchorCursor: String?)

enum class HomeFlowPhase { LOADING, ACTIVE, DONE, ERROR }

/**
 * The 분류·목적 확인 / 정보 보완 flow (C7). [upcoming] is at most the two cards stacked behind
 * [current]. The counter reads `position / total`: [position] = [passed] + 1 while a card shows
 * ([passed] otherwise), [total] = [passed] + the cards left from the current one. [passed] counts
 * this session's temporary passes (C7 saves nothing). [error] belongs to [HomeFlowPhase.ERROR] only;
 * a failed foreground or next-page read keeps the queue silently. [refreshing]: a foreground window
 * is in flight.
 */
data class HomeFlowState(
    val group: HomeActionGroup? = null,
    val phase: HomeFlowPhase = HomeFlowPhase.LOADING,
    val current: HomeFlowCard? = null,
    val upcoming: List<HomeFlowCard> = emptyList(),
    val position: Int = 0,
    val total: Int = 0,
    val passed: Int = 0,
    val error: ClientError? = null,
    val refreshing: Boolean = false,
)
