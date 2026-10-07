package app.home

import app.persistence.inTransaction
import app.purpose.*
import app.wishlist.WishlistReadRepository
import java.util.UUID
import javax.sql.DataSource

class HomeReadService(private val dataSource: DataSource) {
    private val home = HomeReadRepository()
    private val items = WishlistReadRepository()
    private val purposes = PurposeRepository()

    fun get(owner: UUID): HomeReadSummary = dataSource.inTransaction(readOnly = true) { c ->
        val groups = home.groupSummaries(c, owner)
        val loaded = items.load(c, owner, groups.flatMap { it.positions }).associateBy { it.id }
        val recent = purposes.page(c, owner, null, 3)
        val previews = purposes.previews(c, owner, recent.map { it.id })
        HomeReadSummary(
            groups.map { group -> HomeReadGroup(group.group, group.count, group.positions.map { loaded.getValue(it.id) }) },
            recent.map { PurposeListEntry(it, previews[it.id].orEmpty()) },
        )
    }
}
