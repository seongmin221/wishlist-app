package app.wishlist.shared.data.fake

import app.wishlist.shared.model.CategoryCommand
import app.wishlist.shared.model.CategoryPatch
import app.wishlist.shared.model.CategoryScope
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.ItemListRepository

/** B2 CAT-01~04 on the Fake store (rules live in [FakeStore]). */
internal class FakeCategoryRepository(private val store: FakeStore) : CategoryRepository {
    override suspend fun catalog(scope: CategoryScope, parentId: String?) = store.catalog(scope, parentId)
    override suspend fun custom(id: String) = store.customCategory(id)
    override suspend fun create(command: CategoryCommand, idempotencyKey: String) = store.createCategory(command, idempotencyKey)
    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch) = store.updateCategory(id, expectedVersion, patch)
}

/** B4 ITEM-02 category and purpose scopes (pages and anchor windows) on the Fake store. */
internal class FakeItemListRepository(private val store: FakeStore) : ItemListRepository {
    override suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int, after: Int) =
        store.itemsByCategoryAround(categoryId, anchor, before, after)
    override suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int, after: Int) =
        store.itemsByPurposeAround(purposeId, anchor, before, after)
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int) = store.itemsByPurpose(purposeId, cursor, limit)
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int) = store.itemsByCategory(categoryId, cursor, limit)
}
