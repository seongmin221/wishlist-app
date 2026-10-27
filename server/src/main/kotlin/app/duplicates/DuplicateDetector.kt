package app.duplicates

import app.extraction.UrlCanonicalizer
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Recomputes the stored identity relation of one item with the owner's other ACTIVE items. Runs inside the analysis
 * transaction that changed the item's identity values, under the owner and item locks; DECIDED pairs never change.
 */
object DuplicateDetector {
    private data class Identity(
        val id: UUID, val createdAt: Instant, val canonical: String?, val gtin: String?, val mpnKey: String?, val shareUrl: String,
    ) {
        val urlKeys: Set<String> get() = setOfNotNull(shareUrl, canonical)
        /** Creation order decides which side is "new"; Postgres orders uuid like its lowercase text. */
        fun newerThan(other: Identity) = createdAt > other.createdAt || (createdAt == other.createdAt && id.toString() > other.id.toString())
    }

    private const val COLUMNS = "id, created_at, canonical_url, product_gtin, product_mpn_key, normalized_source_url, source_url"

    fun refresh(c: Connection, itemId: UUID) {
        val (owner, item) = c.prepareStatement("select owner_id, lifecycle_status, $COLUMNS from wishlist_items where id=?").use { s ->
            s.setObject(1, itemId)
            s.executeQuery().use { r -> check(r.next()); if (r.getString(2) != "ACTIVE") return; r.getObject(1, UUID::class.java) to identity(r) }
        }
        val related = candidates(c, owner, item).mapNotNull { other -> bases(item, other).takeIf { it.isNotEmpty() }?.let { other to it } }
        for ((other, bases) in related) {
            val (new, existing) = if (item.newerThan(other)) item to other else other to item
            upsert(c, owner, new.id, existing.id, bases)
        }
        withdrawOthers(c, item.id, related.map { it.first.id })
    }

    private fun candidates(c: Connection, owner: UUID, item: Identity): List<Identity> = c.prepareStatement("""
        select $COLUMNS from wishlist_items
        where owner_id=? and lifecycle_status='ACTIVE' and id<>?
          and (canonical_url=? or product_gtin=? or product_mpn_key=? or normalized_source_url=any(?) or canonical_url=any(?)
               or normalized_source_url is null)
    """.trimIndent()).use { s ->
        val keys = c.createArrayOf("text", item.urlKeys.toTypedArray())
        try {
            s.setObject(1, owner); s.setObject(2, item.id); s.setString(3, item.canonical); s.setString(4, item.gtin)
            s.setString(5, item.mpnKey); s.setArray(6, keys); s.setArray(7, keys)
            s.executeQuery().use { r -> buildList { while (r.next()) add(identity(r)) } }
        } finally { keys.free() }
    }

    private fun bases(a: Identity, b: Identity): List<DuplicateBasis> = buildList {
        if (a.canonical != null && a.canonical == b.canonical) add(DuplicateBasis.CANONICAL_URL)
        if (a.gtin != null && a.gtin == b.gtin) add(DuplicateBasis.GTIN)
        if (a.mpnKey != null && a.mpnKey == b.mpnKey) add(DuplicateBasis.BRAND_MPN)
        if (a.urlKeys.any { it in b.urlKeys }) add(DuplicateBasis.SOURCE_URL)
    }

    /** New pairs open at version 1; OPEN pairs change only when bases change; WITHDRAWN pairs reopen. */
    private fun upsert(c: Connection, owner: UUID, new: UUID, existing: UUID, bases: List<DuplicateBasis>) {
        c.prepareStatement("""
            insert into duplicate_candidates(id, owner_id, new_item_id, existing_item_id, bases) values (?,?,?,?,?)
            on conflict (new_item_id, existing_item_id) do update set bases=excluded.bases, status='OPEN',
                version=duplicate_candidates.version+1, updated_at=clock_timestamp()
            where duplicate_candidates.status='WITHDRAWN' or (duplicate_candidates.status='OPEN' and duplicate_candidates.bases<>excluded.bases)
        """.trimIndent()).use { s ->
            val array = c.createArrayOf("text", bases.map { it.name }.toTypedArray())
            try {
                s.setObject(1, UUID.randomUUID()); s.setObject(2, owner); s.setObject(3, new); s.setObject(4, existing); s.setArray(5, array)
                s.executeUpdate()
            } finally { array.free() }
        }
    }

    /** OPEN pairs of the item with another ACTIVE item that no longer relates; inactive counterparts keep their row. */
    private fun withdrawOthers(c: Connection, itemId: UUID, related: List<UUID>) {
        c.prepareStatement("""
            update duplicate_candidates d set status='WITHDRAWN', version=d.version+1, updated_at=clock_timestamp()
            from wishlist_items other
            where d.status='OPEN' and (d.new_item_id=? or d.existing_item_id=?)
              and other.id = case when d.new_item_id=? then d.existing_item_id else d.new_item_id end
              and other.lifecycle_status='ACTIVE' and other.id <> all(?)
        """.trimIndent()).use { s ->
            val ids = c.createArrayOf("uuid", related.toTypedArray())
            try {
                s.setObject(1, itemId); s.setObject(2, itemId); s.setObject(3, itemId); s.setArray(4, ids); s.executeUpdate()
            } finally { ids.free() }
        }
    }

    private fun identity(r: ResultSet) = Identity(
        r.getObject("id", UUID::class.java), r.getTimestamp("created_at").toInstant(), r.getString("canonical_url"),
        r.getString("product_gtin"), r.getString("product_mpn_key"),
        // Rows saved before B9 have no stored normalized share URL; normalize them here.
        r.getString("normalized_source_url") ?: UrlCanonicalizer.normalize(r.getString("source_url")),
    )
}
