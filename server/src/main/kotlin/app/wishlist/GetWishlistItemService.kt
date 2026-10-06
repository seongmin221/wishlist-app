package app.wishlist

import java.util.UUID
import javax.sql.DataSource

class GetWishlistItemService(private val repository: WishlistItemRepository) {
    constructor(dataSource: DataSource) : this(WishlistItemRepository(dataSource))

    fun get(ownerId: UUID, itemId: UUID): WishlistItem? =
        repository.findOwned(ownerId, itemId)?.takeUnless {
            it.storedState.state.lifecycleStatus == LifecycleStatus.DELETED
        }
}
