package app.candidates

import app.category.CategoryRepository
import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import app.wishlist.ItemMutationRepository
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * CAT-06/PUR-06. Owner lock -> success receipt (same version and token replays, anything else is 404) -> live resource
 * -> purposes (SQL ID order) -> affected items (SQL ID order) -> whole-impact fingerprint -> writes and receipt.
 * The live-resource repositories are only consulted when no receipt exists, so a resent success stays 204.
 */
class StructureDeletionService(private val dataSource: DataSource) {
    private val categories = CategoryRepository()

    fun delete(owner: UUID, kind: DeletionKind, id: UUID, expectedVersion: Int, impactToken: String): Unit = dataSource.inTransaction { c ->
        OwnerStructureLock.lock(c, owner)
        receipt(c, owner, kind, id)?.let { (version, token) ->
            if (version == expectedVersion && token == impactToken) return@inTransaction
            throw CandidateException(kind.notFound)
        }
        when (kind) {
            DeletionKind.CATEGORY -> categories.find(c, owner, id, lock = true)
            DeletionKind.PURPOSE -> TODO("B8c")
        } ?: throw CandidateException(kind.notFound)
        val ids = StructureImpacts.affected(c, owner, kind, id).map { it.first }
        ItemMutationRepository.lockPurposes(c, owner, purposesOf(c, owner, ids))
        val items = ItemMutationRepository.lockAll(c, owner, ids)
        val resource = checkNotNull(StructureImpacts.live(c, owner, kind, id))
        val token = StructureImpacts.fingerprint(owner, kind, id, resource, items.map { it.id to it.version })
        if (resource.version != expectedVersion || token != impactToken) throw CandidateException(kind.conflict)
        when (kind) {
            DeletionKind.CATEGORY -> deleteCategory(c, owner, id, ids)
            DeletionKind.PURPOSE -> TODO("B8c")
        }
        saveReceipt(c, owner, kind, id, expectedVersion, impactToken)
    }

    /**
     * References leave with a CATEGORY override so no later Worker result can refill the slot; purpose, review,
     * metadata and analysis stay. ARCHIVED/DELETED rows keep their reference for snapshots and diagnostics.
     */
    private fun deleteCategory(c: Connection, owner: UUID, id: UUID, items: List<UUID>) {
        if (items.isNotEmpty()) c.prepareStatement("""
            update wishlist_items set custom_category_id=null, category_source=null, category_missing_reason='CUSTOM_CATEGORY_DELETED',
                user_override_fields=array(select distinct f from unnest(user_override_fields || array['CATEGORY']) f order by f),
                version=version+1, updated_at=clock_timestamp()
            where owner_id=? and id = any(?)
        """.trimIndent()).use { s ->
            s.setObject(1, owner); s.setArray(2, c.createArrayOf("uuid", items.toTypedArray()))
            check(s.executeUpdate() == items.size)
        }
        c.prepareStatement("update custom_categories set deleted_at=clock_timestamp(), version=version+1, updated_at=clock_timestamp() where owner_id=? and id=?")
            .use { s -> s.setObject(1, owner); s.setObject(2, id); check(s.executeUpdate() == 1) }
    }

    private fun purposesOf(c: Connection, owner: UUID, items: List<UUID>): List<UUID> = if (items.isEmpty()) emptyList() else c.prepareStatement(
        "select distinct purpose_id from wishlist_items where owner_id=? and id = any(?) and purpose_id is not null",
    ).use { s ->
        s.setObject(1, owner); s.setArray(2, c.createArrayOf("uuid", items.toTypedArray()))
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
    }

    private fun receipt(c: Connection, owner: UUID, kind: DeletionKind, id: UUID): Pair<Int, String>? = c.prepareStatement(
        "select expected_version, impact_token from structure_deletion_receipts where owner_id=? and kind=? and resource_id=?",
    ).use { s ->
        s.setObject(1, owner); s.setString(2, kind.name); s.setObject(3, id)
        s.executeQuery().use { r -> if (r.next()) r.getInt(1) to r.getString(2) else null }
    }

    private fun saveReceipt(c: Connection, owner: UUID, kind: DeletionKind, id: UUID, version: Int, token: String) = c.prepareStatement(
        "insert into structure_deletion_receipts(owner_id,kind,resource_id,expected_version,impact_token) values (?,?,?,?,?)",
    ).use { s ->
        s.setObject(1, owner); s.setString(2, kind.name); s.setObject(3, id); s.setInt(4, version); s.setString(5, token)
        check(s.executeUpdate() == 1)
    }
}
