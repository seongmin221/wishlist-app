package app.wishlist

import app.category.CategoryRef
import java.sql.Connection
import java.util.UUID

class WishlistReadRepository {
    fun scopeError(c: Connection, owner: UUID, scope: ReadScope): ReadResult? {
        val target = when (scope) {
            is ReadScope.Category -> (scope.ref as? CategoryRef.Custom)?.let { Triple("custom_categories", "deleted_at is null", it.id) }
            is ReadScope.Purpose -> Triple("purposes", "lifecycle_status='ACTIVE'", scope.id)
            else -> null
        } ?: return null
        val found = c.prepareStatement("select 1 from ${target.first} where owner_id=? and id=? and ${target.second}").use { s ->
            s.setObject(1,owner);s.setObject(2,target.third);s.executeQuery().use { it.next() }
        }
        return if (found) null else if (scope is ReadScope.Purpose) ReadResult.PurposeNotFound else ReadResult.CategoryNotFound
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

}
