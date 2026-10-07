package app.wishlist.shared.data.fake

import app.wishlist.shared.repository.CatalogRepository

/** In-memory seed queries; no catalog/list wire API implementation is implied. */
internal class FakeCatalogRepository(private val store: FakeStore) : CatalogRepository {
    override suspend fun categories() = store.categories()
    override suspend fun purposes() = store.purposes()
    override suspend fun items(categoryId: String?, purposeId: String?) = store.items(categoryId, purposeId)
}
