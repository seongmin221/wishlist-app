package app.wishlist.shared.data.fake

import app.wishlist.shared.repository.*

class FakeItemRepository(private val store: FakeStore) : CreateItemRepository, GetItemRepository {
    override suspend fun create(command: CreateItemCommand) = store.create(command)
    override suspend fun get(id: String) = store.get(id)
}
