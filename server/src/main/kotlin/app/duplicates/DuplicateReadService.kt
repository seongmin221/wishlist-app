package app.duplicates

import app.persistence.inTransaction
import app.wishlist.DeletionImpacts
import app.wishlist.LifecycleStatus
import app.wishlist.ReadPosition
import app.wishlist.WishlistItem
import app.wishlist.WishlistItemRepository
import app.wishlist.WishlistReadRepository
import java.sql.Connection
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class DuplicateException(
    val code: String,
    val currentVersion: Int? = null,
    val itemId: UUID? = null,
    val fields: Set<String> = emptySet(),
) : RuntimeException(code)

object DuplicateLimits {
    const val DEFAULT_PAGE = 20
    const val MAX_PAGE = 50
}

/** One visible OPEN pair from the new item's side; [kind] and [allowedDecisions] are derived from live states. */
data class DuplicateCandidateView(
    val id: UUID,
    val kind: DuplicateKind,
    val bases: List<DuplicateBasis>,
    val version: Int,
    val allowedDecisions: List<DuplicateDecision>,
    val existingItem: WishlistItem,
)

data class DuplicatePage(val item: WishlistItem, val candidates: List<DuplicateCandidateView>, val totalCount: Long, val next: ReadPosition?)

/** DUP-01. Item, page and count come from one repeatable-read snapshot; existing items in createdAt DESC, id DESC. */
class DuplicateReadService(private val dataSource: DataSource) {
    private val items = WishlistItemRepository(dataSource)
    private val reader = WishlistReadRepository()

    private data class Row(val id: UUID, val bases: List<DuplicateBasis>, val version: Int, val kind: DuplicateKind, val position: ReadPosition)

    fun list(owner: UUID, itemId: UUID, limit: Int, after: ReadPosition?): DuplicatePage {
        require(limit in 1..DuplicateLimits.MAX_PAGE)
        return dataSource.inTransaction(readOnly = true) { c ->
            val item = items.findOwned(c, owner, itemId)?.takeUnless { it.storedState.state.lifecycleStatus == LifecycleStatus.DELETED }
                ?: throw DuplicateException("WISHLIST_ITEM_NOT_FOUND")
            val rows = page(c, owner, itemId, limit + 1, after)
            val page = rows.take(limit)
            val existing = reader.load(c, owner, page.map { it.position }).associateBy { it.id }
            DuplicatePage(
                DeletionImpacts.attach(c, owner, item),
                page.map { row ->
                    DuplicateCandidateView(row.id, row.kind, row.bases, row.version, DuplicateKinds.allowedDecisions(row.kind),
                        DeletionImpacts.attach(c, owner, existing.getValue(row.position.id)))
                },
                count(c, owner, itemId),
                page.lastOrNull()?.takeIf { rows.size > limit }?.position,
            )
        }
    }

    private fun page(c: Connection, owner: UUID, itemId: UUID, limit: Int, after: ReadPosition?): List<Row> =
        c.prepareStatement("""${visible()} select id, bases, version, kind, created_at, existing_id from visible
            where ${if (after == null) "true" else "(created_at, existing_id) < (?, ?)"}
            order by created_at desc, existing_id desc limit ?""").use { s ->
            var i = 1
            s.setObject(i++, owner); s.setObject(i++, itemId)
            if (after != null) { s.setObject(i++, after.createdAt.atOffset(ZoneOffset.UTC)); s.setObject(i++, after.id) }
            s.setInt(i, limit)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val bases = (r.getArray(2).array as Array<*>).map { DuplicateBasis.valueOf(it as String) }
                        add(Row(r.getObject(1, UUID::class.java), bases, r.getInt(3), DuplicateKind.valueOf(r.getString(4)),
                            ReadPosition(r.getTimestamp(5).toInstant(), r.getObject(6, UUID::class.java))))
                    }
                }
            }
        }

    private fun count(c: Connection, owner: UUID, itemId: UUID): Long =
        c.prepareStatement("${visible()} select count(*) from visible").use { s ->
            s.setObject(1, owner); s.setObject(2, itemId)
            s.executeQuery().use { r -> check(r.next()); r.getLong(1) }
        }

    /** Pairs shown to the user: OPEN, both items ACTIVE, and a kind under the live states. */
    private fun visible() = """with visible as (select * from (
        select d.id, d.bases, d.version, e.id existing_id, e.created_at, ${DuplicateKinds.kindSql("n", "e", "d")} kind
        from duplicate_candidates d
        join wishlist_items n on n.id = d.new_item_id
        join wishlist_items e on e.id = d.existing_item_id
        where d.owner_id = ? and d.new_item_id = ? and d.status = 'OPEN' and n.lifecycle_status = 'ACTIVE' and e.lifecycle_status = 'ACTIVE'
    ) pairs where kind is not null)"""
}
