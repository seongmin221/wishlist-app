package app.wishlist.shared.data.fake

import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.repository.*

internal class FakeItemRepository(private val store: FakeStore) : SnapshotCreateItemRepository, GetItemRepository {
    override suspend fun create(command: CreateItemCommand) = store.create(command)
    override suspend fun create(command: CreateItemCommand, expected: SessionSnapshot) = store.create(command, expected)
    override suspend fun get(id: String) = store.get(id)
}
