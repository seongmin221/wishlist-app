package app.wishlist.shared.data.fake

import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.repository.HomeRepository

/** B4 HOME-01/02 on the Fake store (rules live in [FakeStore]). */
internal class FakeHomeRepository(private val store: FakeStore) : HomeRepository {
    override suspend fun summary() = store.homeSummary()
    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int) = store.homeGroup(group, cursor, limit)
    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int) =
        store.homeGroupAround(group, anchor, before, after)
}
