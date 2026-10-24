package app.wishlist.shared.data.local

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.HomeRepository
import app.wishlist.shared.repository.LocalStore

/**
 * HOME-01/02 decorator, like [CachedItemListRepository]: preview, page and window cards are the GET
 * representation, so they refresh `item_cache` with the GET rule. A cache write failure does not
 * fail the answer; the account is the call's snapshot, refused by the store gate after a switch.
 */
internal class CachedHomeRepository(
    private val delegate: HomeRepository,
    private val store: LocalStore,
    private val session: AuthSession,
) : HomeRepository {
    override suspend fun summary() = cached({ summary -> summary.groups.flatMap { group -> group.previews.map { it.item } } }) { summary() }

    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int) = cached(ItemPage::items) { group(group, cursor, limit) }

    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int) =
        cached(ItemPage::items) { groupAround(group, anchor, before, after) }

    private suspend inline fun <T> cached(cards: (T) -> List<WishlistItem>, read: HomeRepository.() -> ClientResult<T>): ClientResult<T> {
        val snapshot = session.state.value
        val result = delegate.read()
        if (result is ClientResult.Success) cards(result.value).takeIf { it.isNotEmpty() }?.let { store.upsertItems(snapshot, it) }
        return result
    }
}
