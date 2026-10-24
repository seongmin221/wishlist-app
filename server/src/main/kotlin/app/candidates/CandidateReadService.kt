package app.candidates

import app.category.CategoryRef
import app.persistence.bindParameters
import app.persistence.inTransaction
import app.wishlist.ReadPosition
import app.wishlist.SqlPredicate
import app.wishlist.WishlistReadRepository
import java.sql.Connection
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * PUR-07. The pool is the owner's ACTIVE, non-PROCESSING items outside the target purpose. Page, count and facets are
 * read from one repeatable-read snapshot; facets always describe the whole pool, never the current filter or page.
 */
class CandidateReadService(private val dataSource: DataSource) {
    private val reader = WishlistReadRepository()

    fun list(owner: UUID, purpose: UUID, filter: CandidateFilter, limit: Int, after: ReadPosition?, facets: Boolean): CandidatePage {
        require(limit in 1..CandidateLimits.MAX_PAGE)
        return dataSource.inTransaction(readOnly = true) { c ->
            if (!exists(c, "select 1 from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'", owner, purpose)) {
                throw CandidateException("PURPOSE_NOT_FOUND")
            }
            val custom = ((filter as? CandidateFilter.Category)?.ref as? CategoryRef.Custom)?.id
            if (custom != null && !exists(c, "select 1 from custom_categories where owner_id=? and id=? and deleted_at is null", owner, custom)) {
                throw CandidateException("CATEGORY_NOT_FOUND")
            }
            val pool = pool(owner, purpose)
            val narrowed = narrow(filter)
            val keyset = after?.let { SqlPredicate(" and (created_at,id) < (?,?)", listOf(it.createdAt.atOffset(ZoneOffset.UTC), it.id)) }
                ?: SqlPredicate("", emptyList())
            val rows = query(c, SqlPredicate("${pool.sql} select id,created_at from pool where ${narrowed.sql}${keyset.sql} " +
                "order by created_at desc,id desc limit ?", pool.parameters + narrowed.parameters + keyset.parameters + (limit + 1))) { r ->
                ReadPosition(r.getTimestamp(2).toInstant(), r.getObject(1, UUID::class.java))
            }
            val page = rows.take(limit)
            val total = query(c, SqlPredicate("${pool.sql} select count(*) from pool where ${narrowed.sql}", pool.parameters + narrowed.parameters)) {
                it.getLong(1)
            }.single()
            CandidatePage(reader.load(c, owner, page), total, page.lastOrNull()?.takeIf { rows.size > limit },
                if (facets) facets(c, pool) else null)
        }
    }

    private fun facets(c: Connection, pool: SqlPredicate): CandidateFacets {
        val categories = query(c, SqlPredicate("""${pool.sql} select coalesce(category_id,custom_category_id::text),parent_id,count(*) from pool
            where parent_id is not null group by 1,2""", pool.parameters)) { CategoryFacet(it.getString(1), it.getString(2), it.getLong(3)) }
        val unassigned = query(c, SqlPredicate("${pool.sql} select count(*) from pool where category_id is null and custom_category_id is null",
            pool.parameters)) { it.getLong(1) }.single()
        // String order of the IDs, not the database collation.
        return CandidateFacets(categories.sortedBy { it.categoryId }, unassigned)
    }

    /** Parent comes from the public row or the live custom row, so a parent filter covers both kinds. */
    private fun pool(owner: UUID, purpose: UUID) = SqlPredicate("""with pool as not materialized (
        select i.id,i.created_at,i.category_id,i.custom_category_id,coalesce(pc.parent_id,cc.parent_id) parent_id
        from wishlist_items i
        left join public_categories pc on pc.id=i.category_id
        left join custom_categories cc on cc.owner_id=i.owner_id and cc.id=i.custom_category_id and cc.deleted_at is null
        where i.owner_id=? and i.lifecycle_status='ACTIVE' and i.analysis_status<>'PROCESSING' and i.purpose_id is distinct from ?
    )""", listOf(owner, purpose))

    private fun narrow(filter: CandidateFilter): SqlPredicate = when (filter) {
        CandidateFilter.None -> SqlPredicate("true", emptyList())
        is CandidateFilter.Parent -> SqlPredicate("parent_id=?", listOf(filter.id))
        is CandidateFilter.Category -> when (val ref = filter.ref) {
            is CategoryRef.Public -> SqlPredicate("category_id=?", listOf(ref.value))
            is CategoryRef.Custom -> SqlPredicate("custom_category_id=?", listOf(ref.id))
        }
    }

    private fun exists(c: Connection, sql: String, owner: UUID, id: UUID): Boolean = c.prepareStatement(sql).use { s ->
        s.setObject(1, owner); s.setObject(2, id); s.executeQuery().use { it.next() }
    }

    private fun <T> query(c: Connection, predicate: SqlPredicate, map: (java.sql.ResultSet) -> T): List<T> =
        c.prepareStatement(predicate.sql).use { s ->
            s.bindParameters(predicate.parameters)
            s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
        }
}
