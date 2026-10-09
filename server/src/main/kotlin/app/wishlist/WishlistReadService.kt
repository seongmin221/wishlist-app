package app.wishlist

import app.persistence.inTransaction
import java.util.UUID
import javax.sql.DataSource

class WishlistReadService(private val dataSource: DataSource) {
    private val repository=WishlistReadRepository()
    private val windowReader=WishlistWindowReader(repository)
    fun read(owner: UUID, query: ReadQuery): ReadResult = dataSource.inTransaction(readOnly=true) { c ->
        repository.scopeError(c,owner,query.scope)?.let { return@inTransaction it }
        ReadResult.Success(windowReader.read(c,owner,query.scope,query.window))
    }
}
