package app.wishlist

import java.sql.Connection
import java.util.UUID

/** What deleting this item changes for its purpose (ITEM-03 confirmation and help text). */
data class DeletionImpact(val purpose: PurposeImpact?)

/** [candidateCount] counts every ACTIVE item of the purpose, including this one. */
data class PurposeImpact(val id: UUID, val name: String, val candidateCount: Int)

internal object DeletionImpacts {
    /** Null for items that cannot be deleted from the active list (ARCHIVED, DELETED tombstones). */
    fun read(c: Connection, owner: UUID, item: WishlistItem): DeletionImpact? {
        if (item.storedState.state.lifecycleStatus != LifecycleStatus.ACTIVE) return null
        val purposeId = item.storedState.purposeId?.let(UUID::fromString) ?: return DeletionImpact(null)
        return c.prepareStatement("""
            select p.name, (select count(*) from wishlist_items i
                where i.owner_id=p.owner_id and i.purpose_id=p.id and i.lifecycle_status='ACTIVE')
            from purposes p where p.owner_id=? and p.id=? and p.lifecycle_status='ACTIVE'
        """.trimIndent()).use { s ->
            s.setObject(1, owner); s.setObject(2, purposeId)
            s.executeQuery().use { r -> DeletionImpact(if (r.next()) PurposeImpact(purposeId, r.getString(1), r.getInt(2)) else null) }
        }
    }

    fun attach(c: Connection, owner: UUID, item: WishlistItem): WishlistItem = item.copy(deletionImpact = read(c, owner, item))
}
