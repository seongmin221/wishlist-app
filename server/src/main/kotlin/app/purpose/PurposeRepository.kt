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

    internal fun row(r: ResultSet) = Purpose(
        r.getObject("id", UUID::class.java),
        PurposeInput(r.getString("name"), r.getString("description"), PurposeColor.valueOf(r.getString("color_key")), PurposeIcon.valueOf(r.getString("icon_key"))),
        r.getInt("version"), r.getInt("membership_version"), r.getLong("candidate_count"),
        r.getObject("activity_at", OffsetDateTime::class.java).toInstant(), PurposeActivityKind.valueOf(r.getString("activity_kind")),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
    )
}
