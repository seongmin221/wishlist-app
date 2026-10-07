package app.purpose

import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class PurposeRepository {
    private val columns = """p.id,p.name,p.description,p.color_key,p.icon_key,p.version,p.membership_version,p.activity_at,
        p.activity_kind,p.created_at,p.updated_at,(select count(*) from wishlist_items i where i.owner_id=p.owner_id
        and i.purpose_id=p.id and i.lifecycle_status='ACTIVE') candidate_count"""

    fun find(c: Connection, owner: UUID, id: UUID, lock: Boolean = false): Purpose? = c.prepareStatement(
        "select $columns from purposes p where p.owner_id=? and p.id=? and p.lifecycle_status='ACTIVE' ${if (lock) "for update of p" else ""}",
    ).use { s -> s.setObject(1, owner); s.setObject(2, id); s.executeQuery().use { r -> if (r.next()) row(r) else null } }

    fun count(c: Connection, owner: UUID, lifecycle: String): Int = c.prepareStatement(
        "select count(*) from purposes where owner_id=? and lifecycle_status=?",
    ).use { s -> s.setObject(1, owner); s.setString(2, lifecycle); s.executeQuery().use { r -> check(r.next()); r.getInt(1) } }

    /** created_at and activity_at share one clock reading so a new purpose sorts by its creation time. */
    fun insert(c: Connection, owner: UUID, input: PurposeInput): UUID {
        val id = UUID.randomUUID()
        c.prepareStatement("""insert into purposes(id,owner_id,name,description,color_key,icon_key,activity_at,created_at,updated_at)
            select ?,?,?,?,?,?,t,t,t from (select clock_timestamp() t) now""").use { s ->
            s.setObject(1, id); s.setObject(2, owner); s.setString(3, input.name); s.setString(4, input.description)
            s.setString(5, input.color.name); s.setString(6, input.icon.name); check(s.executeUpdate() == 1)
        }
        return id
    }

    fun update(c: Connection, owner: UUID, id: UUID, input: PurposeInput) {
        c.prepareStatement("""update purposes set name=?,description=?,color_key=?,icon_key=?,version=version+1,
            updated_at=clock_timestamp() where owner_id=? and id=?""").use { s ->
            s.setString(1, input.name); s.setString(2, input.description); s.setString(3, input.color.name); s.setString(4, input.icon.name)
            s.setObject(5, owner); s.setObject(6, id); check(s.executeUpdate() == 1)
        }
    }

    fun page(c: Connection, owner: UUID, after: PurposeCursorPosition?, limit: Int): List<Purpose> {
        val keyset = if (after == null) "" else "and (p.activity_at,p.id) < (?,?)"
        return c.prepareStatement("""select $columns from purposes p where p.owner_id=? and p.lifecycle_status='ACTIVE' $keyset
            order by p.activity_at desc,p.id desc limit ?""").use { s ->
            var index = 1
            s.setObject(index++, owner)
            if (after != null) { s.setObject(index++, after.activityAt.atOffset(java.time.ZoneOffset.UTC)); s.setObject(index++, after.id) }
            s.setInt(index, limit)
            s.executeQuery().use { r -> buildList { while (r.next()) add(row(r)) } }
        }
    }

    /** Newest four saved ACTIVE candidates per purpose; same order as the ITEM-02 purpose filter. */
    fun previews(c: Connection, owner: UUID, ids: List<UUID>): Map<UUID, List<PurposePreview>> {
        if (ids.isEmpty()) return emptyMap()
        val array = c.createArrayOf("uuid", ids.toTypedArray())
        try {
            return c.prepareStatement("""select purpose_id,id,product_image_url from (
                select purpose_id,id,product_image_url,created_at,row_number() over (partition by purpose_id order by created_at desc,id desc) rank
                from wishlist_items where owner_id=? and lifecycle_status='ACTIVE' and purpose_id=any(?)) ranked
                where rank<=4 order by purpose_id,rank""").use { s ->
                s.setObject(1, owner); s.setArray(2, array)
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java) to
                    PurposePreview(r.getObject(2, UUID::class.java), r.getString(3))) } }
            }.groupBy({ it.first }, { it.second })
        } finally { array.free() }
    }

    internal fun row(r: ResultSet) = Purpose(
        r.getObject("id", UUID::class.java),
        PurposeInput(r.getString("name"), r.getString("description"), PurposeColor.valueOf(r.getString("color_key")), PurposeIcon.valueOf(r.getString("icon_key"))),
        r.getInt("version"), r.getInt("membership_version"), r.getLong("candidate_count"),
        r.getObject("activity_at", OffsetDateTime::class.java).toInstant(), PurposeActivityKind.valueOf(r.getString("activity_kind")),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )
}
