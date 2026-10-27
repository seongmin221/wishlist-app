package app.wishlist.shared.presentation

import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.WishlistItem

/**
 * Applied once by the platform when [token] changes: scroll so [resolvedItemId] is the first visible
 * card (null: the top of the list). [sameItem] is false when the requested card left the list and the
 * server resolved a fallback; that is not an error.
 */
data class AnchorRestore(val resolvedItemId: String?, val sameItem: Boolean, val token: Long)

/**
 * The cards a list screen holds (C7 목록 window), newest first: [previousCursor] reads newer cards
 * in front, [nextCursor] older cards behind, and [anchorOf] gives a card's anchor token for the next
 * foreground/pull-to-refresh window. Immutable; the owning Presenter keeps it on its lane.
 */
internal data class ItemWindow(
    val items: List<WishlistItem>,
    val totalCount: Int?,
    val nextCursor: String?,
    val previousCursor: String?,
    private val anchors: Map<String, String>,
) {
    fun anchorOf(itemId: String): String? = anchors[itemId]

    /** A first page or an anchor window: everything held before is dropped. */
    fun replace(page: ItemPage) = ItemWindow(page.items, page.totalCount, page.nextCursor, page.previousCursor, page.anchorCursors)

    /** An older page behind: the newer edge stays; cards already held are not repeated. */
    fun append(page: ItemPage): ItemWindow {
        val known = items.mapTo(hashSetOf()) { it.id }
        return copy(items = items + page.items.filter { it.id !in known }, totalCount = page.totalCount,
            nextCursor = page.nextCursor, anchors = anchors + page.anchorCursors)
    }

    /** A newer page in front: the older edge stays; cards already held are not repeated. */
    fun prepend(page: ItemPage): ItemWindow {
        val known = items.mapTo(hashSetOf()) { it.id }
        return copy(items = page.items.filter { it.id !in known } + items, totalCount = page.totalCount,
            previousCursor = page.previousCursor, anchors = anchors + page.anchorCursors)
    }

    companion object {
        val Empty = ItemWindow(emptyList(), null, null, null, emptyMap())
    }
}
