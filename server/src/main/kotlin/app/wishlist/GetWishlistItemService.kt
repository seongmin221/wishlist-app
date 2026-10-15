package app.wishlist

import java.util.UUID
import app.persistence.inTransaction
import javax.sql.DataSource

class GetWishlistItemService(
    private val dataSource: DataSource,
    /** Test seam between the item read and the impact read. */
    private val beforeImpactRead: () -> Unit = {},
) {
    private val repository = WishlistItemRepository(dataSource)

    /** One REPEATABLE READ snapshot so the impact always describes the purpose of the item that is returned. */
    fun get(ownerId: UUID, itemId: UUID): WishlistItem? = dataSource.inTransaction(readOnly = true) { c ->
        repository.findOwned(c, ownerId, itemId)
            ?.takeUnless { it.storedState.state.lifecycleStatus == LifecycleStatus.DELETED }
            ?.let { item -> beforeImpactRead(); DeletionImpacts.attach(c, ownerId, item) }
    }
}
