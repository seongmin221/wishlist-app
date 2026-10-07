package app.wishlist.shared.presentation

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.model.WishlistItem

/**
 * Item detail screen state. UI/navigation callbacks never live here. C4 extends this with the
 * detail intents' state; the item/loading/error axes stay.
 */
data class ItemDetailState(
    val item: WishlistItem?,
    val loading: Boolean,
    val error: ClientError?,
) {
    companion object {
        /** Nothing loaded: before the first load and right after an account/generation change. */
        val Initial = ItemDetailState(item = null, loading = false, error = null)
    }
}
