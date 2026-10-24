package app.candidates

import app.persistence.inTransaction
import app.wishlist.ReadPosition
import app.wishlist.WishlistReadRepository
import java.security.MessageDigest
import java.sql.Connection
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * CAT-05/PUR-05. The list is createdAt DESC, id DESC with an exclusive `(created_at,id) < (?,?)` boundary; the token is
 * computed separately over every ACTIVE affected item in ID order, so it does not depend on the page or the limit.
 */
class DeletionImpactService(private val dataSource: DataSource) {
    private val reader = WishlistReadRepository()

    fun get(owner: UUID, kind: DeletionKind, id: UUID, limit: Int, after: ReadPosition?, expectedFingerprint: String?): StructureImpact {
        require(limit in 1..CandidateLimits.MAX_PAGE)
        return dataSource.inTransaction(readOnly = true) { c ->
            val resource = StructureImpacts.live(c, owner, kind, id) ?: throw CandidateException(kind.notFound)
            val affected = StructureImpacts.affected(c, owner, kind, id)
            val token = StructureImpacts.fingerprint(owner, kind, id, resource, affected)
            if (expectedFingerprint != null && expectedFingerprint != token) throw CandidateException(kind.conflict)
            val keyset = if (after == null) "" else " and (created_at,id) < (?,?)"
            val rows = c.prepareStatement("select id,created_at from wishlist_items where ${StructureImpacts.predicate(kind)}$keyset " +
                "order by created_at desc,id desc limit ?").use { s ->
                s.setObject(1, owner); s.setObject(2, id)
                var i = 3
                if (after != null) { s.setObject(i++, after.createdAt.atOffset(ZoneOffset.UTC)); s.setObject(i++, after.id) }
                s.setInt(i, limit + 1)
                s.executeQuery().use { r -> buildList { while (r.next()) add(ReadPosition(r.getTimestamp(2).toInstant(), r.getObject(1, UUID::class.java))) } }
            }
            val page = rows.take(limit)
            StructureImpact(resource.version, token, affected.size.toLong(), reader.load(c, owner, page), page.lastOrNull()?.takeIf { rows.size > limit })
        }
    }
}

internal data class LiveResource(val version: Int, val membershipVersion: Int?)

/** Shared by the impact read and the final deletion check so both compute the same fingerprint. */
internal object StructureImpacts {
    fun predicate(kind: DeletionKind): String = "owner_id=? and lifecycle_status='ACTIVE' and " +
        if (kind == DeletionKind.CATEGORY) "custom_category_id=?" else "purpose_id=?"

    fun live(c: Connection, owner: UUID, kind: DeletionKind, id: UUID): LiveResource? = c.prepareStatement(when (kind) {
        DeletionKind.CATEGORY -> "select version, null from custom_categories where owner_id=? and id=? and deleted_at is null"
        DeletionKind.PURPOSE -> "select version, membership_version from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'"
    }).use { s ->
        s.setObject(1, owner); s.setObject(2, id)
        s.executeQuery().use { r -> if (r.next()) LiveResource(r.getInt(1), r.getObject(2) as Int?) else null }
    }

    /** Every ACTIVE affected item with its version, in SQL ID order. */
    fun affected(c: Connection, owner: UUID, kind: DeletionKind, id: UUID): List<Pair<UUID, Int>> =
        c.prepareStatement("select id,version from wishlist_items where ${predicate(kind)} order by id").use { s ->
            s.setObject(1, owner); s.setObject(2, id)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java) to r.getInt(2)) } }
        }

    fun fingerprint(owner: UUID, kind: DeletionKind, id: UUID, resource: LiveResource, items: List<Pair<UUID, Int>>): String {
        val canonical = "v1|$owner|${kind.name}|$id|${resource.version}|${resource.membershipVersion ?: "-"}|" +
            items.sortedWith(compareBy(SQL_UUID_ORDER) { it.first }).joinToString(",") { (item, version) -> "$item:$version" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
