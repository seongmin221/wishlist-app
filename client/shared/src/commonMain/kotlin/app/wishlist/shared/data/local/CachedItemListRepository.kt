package app.wishlist.shared.data.local

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.repository.ItemListRepository
import app.wishlist.shared.repository.LocalStore

/**
 * ITEM-02 decorator: a page's or window's cards are the GET representation, so they refresh `item_cache` with
 * the GET rule (same version overwrites). That keeps the detail cache's purpose/category names in
 * step with the list the user just saw (C4 limit 1). A cache write failure does not fail the page.
 * The account is the one of the call's snapshot; the store gate refuses it after a switch.
 */
internal class CachedItemListRepository(
    private val delegate: ItemListRepository,
    private val store: LocalStore,
    private val session: AuthSession,
) : ItemListRepository {
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int) = cached { byPurpose(purposeId, cursor, limit) }

    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int) = cached { byCategory(categoryId, cursor, limit) }

    override suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int, after: Int) =
        cached { byCategoryAround(categoryId, anchor, before, after) }

    override suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int, after: Int) =
        cached { byPurposeAround(purposeId, anchor, before, after) }

    /** Windows are the same GET cards as pages, so both refresh the cache. */
    private suspend inline fun cached(read: ItemListRepository.() -> ClientResult<ItemPage>): ClientResult<ItemPage> {
        val snapshot = session.state.value
        val result = delegate.read()
        if (result is ClientResult.Success && result.value.items.isNotEmpty()) store.upsertItems(snapshot, result.value.items)
        return result
    }
}
