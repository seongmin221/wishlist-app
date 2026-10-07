package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore

/**
 * Sole owner of GET cache synchronization. Flow: snapshot -> cache read -> snapshot check ->
 * delegate -> cache write -> same-snapshot check -> return. The caller always receives the delegate's
 * response (a skipped same-version cache write must not hide a newer display name). NOT_FOUND removes
 * only versions up to the one observed before the request, so a late 404 cannot erase a newer cache.
 */
internal class CachedGetItemRepository(
    internal val delegate: GetItemRepository,
    private val localStore: LocalStore,
    private val session: AuthSession,
) : GetItemRepository {
    private fun changed(snapshot: SessionSnapshot) = session.state.value != snapshot

    override suspend fun get(id: String): ClientResult<WishlistItem> {
        val snapshot = session.state.value
        if (snapshot.accountId == null) return delegate.get(id)

        val observed = when (val read = localStore.cachedItem(snapshot, id)) {
            is ClientResult.Failure -> return read
            is ClientResult.Success -> read.value?.version
        }
        if (changed(snapshot)) return sessionChanged()

        val response = delegate.get(id)
        if (changed(snapshot)) return sessionChanged()

        when (response) {
            is ClientResult.Success -> {
                val write = localStore.upsertItem(snapshot, response.value)
                if (write is ClientResult.Failure) return write
            }
            is ClientResult.Failure -> if (response.error.kind == ErrorKind.NOT_FOUND && observed != null) {
                val remove = localStore.removeCachedItem(snapshot, id, observed)
                if (remove is ClientResult.Failure) return remove
            }
        }
        return if (changed(snapshot)) sessionChanged() else response
    }

    private fun sessionChanged() = ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))
}
