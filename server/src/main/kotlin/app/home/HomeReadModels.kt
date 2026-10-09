package app.home

import app.purpose.PurposeListEntry
import app.wishlist.*

data class HomeGroupKeys(val group: HomeActionGroup, val count: Long, val positions: List<ReadPosition>)
data class HomeReadGroup(val group: HomeActionGroup, val count: Long, val items: List<WishlistItem>)
data class HomeReadSummary(val actionGroups: List<HomeReadGroup>, val recentPurposes: List<PurposeListEntry>)
