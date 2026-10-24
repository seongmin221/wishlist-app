package app.testutil

import java.util.UUID
import javax.sql.DataSource

/** Pins an item's creation time so keyset tests control ties; updated_at follows it. */
fun setCreatedAt(source: DataSource, item: UUID, instant: String) =
    analysisSql(source, "update wishlist_items set created_at='$instant', updated_at='$instant' where id='$item'")

/** Purpose metadata, membership and activity as text for before/after comparisons. */
fun purposeState(source: DataSource, id: UUID): Map<String, String?> = source.connection.use { c ->
    c.prepareStatement("""select version::text, membership_version::text, activity_at::text, activity_kind, lifecycle_status,
        (select count(*) from wishlist_items i where i.owner_id=p.owner_id and i.purpose_id=p.id and i.lifecycle_status='ACTIVE')::text
        from purposes p where id=?""").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { r ->
            check(r.next())
            mapOf("version" to r.getString(1), "membership" to r.getString(2), "activity" to r.getString(3), "kind" to r.getString(4),
                "lifecycle" to r.getString(5), "count" to r.getString(6))
        }
    }
}

/** Every column of the listed items as JSON text, to prove a rejected command changed nothing. */
fun itemSnapshot(source: DataSource, ids: Collection<UUID>): List<String> = source.connection.use { c ->
    c.prepareStatement("select to_jsonb(t)::text from wishlist_items t where id=any(?) order by id").use { s ->
        s.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
}

/** PostgreSQL orders UUIDs by their bytes, which is the order of their canonical lowercase text. */
val sqlUuidOrder: Comparator<UUID> = compareBy { it.toString() }

fun archivePurpose(source: DataSource, id: UUID, lifecycle: String = "ARCHIVED") =
    analysisSql(source, "update purposes set lifecycle_status='$lifecycle' where id='$id'")
