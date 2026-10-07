package app.wishlist

import app.category.CategoryRef
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.ZoneOffset
import java.util.UUID

class WishlistReadRepository {
    fun validateScope(c: Connection, owner: UUID, scope: ReadScope): ReadScopeAvailability {
        val target = when (scope) {
            is ReadScope.Category -> (scope.ref as? CategoryRef.Custom)?.let { Triple("custom_categories", "deleted_at is null", it.id) }
            is ReadScope.Purpose -> Triple("purposes", "lifecycle_status='ACTIVE'", scope.id)
            else -> null
        } ?: return ReadScopeAvailability.AVAILABLE
        val found = c.prepareStatement("select 1 from ${target.first} where owner_id=? and id=? and ${target.second}").use { s ->
            s.setObject(1,owner);s.setObject(2,target.third);s.executeQuery().use { it.next() }
        }
        return if (found) ReadScopeAvailability.AVAILABLE else if (scope is ReadScope.Purpose) ReadScopeAvailability.PURPOSE_NOT_FOUND else ReadScopeAvailability.CATEGORY_NOT_FOUND
    }

    fun count(c: Connection, owner: UUID, scope: ReadScope): Long {
        val from=source(scope);val where=predicate(owner,scope)
        return c.prepareStatement("select count(*) from ${from.sql} where ${where.sql}").use { s ->
            bind(s,from.parameters+where.parameters)
            s.executeQuery().use { r -> check(r.next());r.getLong(1) }
        }
    }

    fun keys(c: Connection, owner: UUID, scope: ReadScope, boundary: ReadPosition?, direction: ReadDirection, limit: Int): List<ReadPosition> {
        val from=source(scope);val where=predicate(owner,scope)
        val comparison=if(boundary==null) "" else " and (i.created_at,i.id) ${if(direction==ReadDirection.OLDER) "<" else ">"} (?,?)"
        val order=if(direction==ReadDirection.OLDER) "desc" else "asc"
        return c.prepareStatement("select i.id,i.created_at from ${from.sql} where ${where.sql}$comparison order by i.created_at $order,i.id $order limit ?").use { s ->
            var n=bind(s,from.parameters+where.parameters)
            if(boundary!=null) { s.setObject(n++,boundary.createdAt.atOffset(ZoneOffset.UTC));s.setObject(n++,boundary.id) }
            s.setInt(n,limit)
            s.executeQuery().use { r -> buildList { while(r.next()) add(position(r)) } }
        }
    }

    fun findAnchor(c: Connection, owner: UUID, scope: ReadScope, id: UUID): ReadPosition? {
        val from=source(scope);val where=predicate(owner,scope)
        return c.prepareStatement("select i.id,i.created_at from ${from.sql} where ${where.sql} and i.id=?").use { s ->
            val n=bind(s,from.parameters+where.parameters);s.setObject(n,id)
            s.executeQuery().use { r -> if(r.next()) position(r) else null }
        }
    }

    fun exists(c: Connection, owner: UUID, scope: ReadScope, boundary: ReadPosition, direction: ReadDirection): Boolean {
        val from=source(scope);val where=predicate(owner,scope)
        return c.prepareStatement("select exists(select 1 from ${from.sql} where ${where.sql} and (i.created_at,i.id) ${if(direction==ReadDirection.OLDER) "<" else ">"} (?,?))").use { s ->
            var n=bind(s,from.parameters+where.parameters);s.setObject(n++,boundary.createdAt.atOffset(ZoneOffset.UTC));s.setObject(n,boundary.id)
            s.executeQuery().use { r -> check(r.next());r.getBoolean(1) }
        }
    }

    /** Positions were selected in the same snapshot; this mapper also serves owned detail states. */
    fun load(c: Connection, owner: UUID, positions: List<ReadPosition>): List<WishlistItem> {
        if(positions.isEmpty()) return emptyList()
        val ids=c.createArrayOf("uuid",positions.map { it.id }.toTypedArray())
        try {
            val items=c.prepareStatement("select ${WishlistItemRowMapper.columns} from wishlist_items i ${WishlistItemRowMapper.joins} where i.owner_id=? and i.id=any(?)").use { s ->
                s.setObject(1,owner);s.setArray(2,ids)
                s.executeQuery().use { r -> buildMap { while(r.next()) { val item=WishlistItemRowMapper.map(r);put(item.id,item) } } }
            }
            return positions.mapNotNull { items[it.id] }
        } finally { ids.free() }
    }

    private fun source(scope: ReadScope): SqlPredicate {
        if(scope !is ReadScope.Action) return SqlPredicate("wishlist_items i", emptyList())
        val action=WishlistReadPredicates.requiredAction("w")
        return SqlPredicate("(select w.*,${action.sql} read_required_action from wishlist_items w) i",action.parameters)
    }
    private fun predicate(owner: UUID, scope: ReadScope): SqlPredicate = when(scope) {
        is ReadScope.Category -> {
            val visible=WishlistReadPredicates.categoryVisible()
            val column=if(scope.ref is CategoryRef.Public) "category_id" else "custom_category_id"
            val value=when(val ref=scope.ref) { is CategoryRef.Public -> ref.value;is CategoryRef.Custom -> ref.id }
            SqlPredicate("i.owner_id=? and ${visible.sql} and i.$column=?",listOf(owner)+visible.parameters+value)
        }
        is ReadScope.Purpose -> SqlPredicate("i.owner_id=? and i.lifecycle_status='ACTIVE' and i.purpose_id=?",listOf(owner,scope.id))
        ReadScope.PurposeUnassigned -> SqlPredicate("i.owner_id=? and i.lifecycle_status='ACTIVE' and i.purpose_id is null",listOf(owner))
        is ReadScope.Action -> SqlPredicate("i.owner_id=? and i.lifecycle_status='ACTIVE' and (${WishlistReadPredicates.homeGroup("i.read_required_action")})=?",listOf(owner,scope.group.name))
    }
    private fun position(r: ResultSet) = ReadPosition(r.getTimestamp("created_at").toInstant(),r.getObject("id",UUID::class.java))
    private fun bind(s: PreparedStatement, parameters: List<Any?>): Int {
        parameters.forEachIndexed { n,v -> s.setObject(n+1,v) };return parameters.size+1
    }
}
