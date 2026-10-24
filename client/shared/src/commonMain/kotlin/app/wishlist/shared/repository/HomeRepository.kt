package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomeSummary
import app.wishlist.shared.model.ItemPage

/**
 * B4 HOME-01 summary and HOME-02 group list. Errors keep the server code: `INVALID_HOME_QUERY`
 * (group, bounds) and `INVALID_HOME_CURSOR` (another group's or a malformed token).
 */
interface HomeRepository {
    suspend fun summary(): ClientResult<HomeSummary>

    /** HOME-02 page, newest first; [cursor] is a page's next or previous cursor. */
    suspend fun group(group: HomeActionGroup, cursor: String? = null, limit: Int = DEFAULT_LIMIT): ClientResult<ItemPage>

    /** HOME-02 window around a card's anchor (preview or page card), with the ITEM-02 fallback rules. */
    suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int = DEFAULT_SPAN, after: Int = DEFAULT_SPAN): ClientResult<ItemPage>

    companion object {
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_SPAN = 20
    }
}
