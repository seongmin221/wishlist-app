package app.wishlist

import java.util.UUID
import javax.sql.DataSource

class GetWishlistItemService(private val repository: WishlistItemStateRepository) {
    constructor(dataSource: DataSource) : this(WishlistItemStateRepository(dataSource))

    fun get(ownerId: UUID, itemId: UUID): WishlistItem? =
        repository.findViewOwned(ownerId, itemId)?.takeUnless {
            it.storedState.state.lifecycleStatus == LifecycleStatus.DELETED
        }
}
