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
        HomeReadSummary(
            groups.map { group -> HomeReadGroup(group.group, group.count, group.positions.map { position ->
                checkNotNull(loaded[position.id]) { "Missing HOME-01 projection for ${position.id} in the same read snapshot" }
            }) },
            purposes.entries(c,owner,recent),
        )
    }
}
