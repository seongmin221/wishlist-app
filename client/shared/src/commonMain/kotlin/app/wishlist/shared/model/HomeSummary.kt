package app.wishlist.shared.model

/** A HOME-01 preview card. [anchorCursor] opens the HOME-02 window of its group at this card. */
data class HomePreview(val item: WishlistItem, val anchorCursor: String)

/** One HOME-01 action group: the exact ACTIVE [count] and at most four newest [previews]. */
data class HomeGroupSummary(val group: HomeActionGroup, val count: Int, val previews: List<HomePreview>)

/**
 * HOME-01. [groups] always holds the three groups in [HomeActionGroup.entries] order (a group the
 * server did not send is count 0). [recentPurposes] is the server's order, at most three, empty ones
 * included. Device-pending submissions are not part of it; the client adds them.
 */
data class HomeSummary(val groups: List<HomeGroupSummary>, val recentPurposes: List<PurposeSummary>)
