package app.category

import app.wishlist.WishlistReadPredicates
import java.sql.Connection
import java.util.UUID

class CategoryRepository {
    private val visibility = WishlistReadPredicates.categoryVisible()
    fun find(connection: Connection, owner: UUID, id: UUID, lock: Boolean = false): CustomCategory? = connection.prepareStatement("""
        select c.*, (select count(*) from wishlist_items i where i.owner_id = c.owner_id and i.custom_category_id = c.id and ${visibility.sql}) item_count
        from custom_categories c where c.owner_id = ? and c.id = ? and c.deleted_at is null ${if (lock) "for update of c" else ""}
    """).use { s ->
        visibility.parameters.forEachIndexed { n,v -> s.setObject(n+1,v) }
        s.setObject(visibility.parameters.size+1, owner); s.setObject(visibility.parameters.size+2, id)
        s.executeQuery().use { r -> if (!r.next()) null else row(r) }
    }

    fun all(connection: Connection, owner: UUID): List<CustomCategory> = connection.prepareStatement("""
        select c.*, (select count(*) from wishlist_items i where i.owner_id = c.owner_id and i.custom_category_id = c.id and ${visibility.sql}) item_count
        from custom_categories c where c.owner_id = ? and c.deleted_at is null order by c.parent_id, c.display_order
    """).use { s -> visibility.parameters.forEachIndexed { n,v -> s.setObject(n+1,v) }; s.setObject(visibility.parameters.size+1, owner); s.executeQuery().use { r -> buildList { while (r.next()) add(row(r)) } } }

    fun count(connection: Connection, owner: UUID): Int = connection.prepareStatement(
        "select count(*) from custom_categories where owner_id = ? and deleted_at is null",
    ).use { statement ->
        statement.setObject(1, owner)
        statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
    }

    fun duplicate(connection: Connection, owner: UUID, parent: String, normalized: String, exceptId: UUID? = null): Boolean =
        connection.prepareStatement("select 1 from custom_categories where owner_id = ? and parent_id = ? and normalized_name = ? and deleted_at is null and (?::uuid is null or id<>?)").use { s ->
            s.setObject(1, owner); s.setString(2, parent); s.setString(3, normalized); s.setObject(4, exceptId); s.setObject(5, exceptId)
            s.executeQuery().use { it.next() }
        }

    fun insert(connection: Connection, owner: UUID, parent: String, input: CategoryInput, eligible: Boolean, reason: String?): UUID {
        val order = connection.prepareStatement("select coalesce(max(display_order)+1, (select count(*)::integer from public_categories where parent_id = ?)) from custom_categories where owner_id = ? and parent_id = ?").use { s ->
            s.setString(1, parent);s.setObject(2, owner);s.setString(3, parent);s.executeQuery().use { r -> check(r.next());r.getInt(1) }
        }
        val id = UUID.randomUUID()
        val examples = connection.createArrayOf("text", input.examples.toTypedArray())
        try { connection.prepareStatement("""insert into custom_categories(id, owner_id, parent_id, name, normalized_name, description, examples, ai_eligible, ai_exclusion_reason, display_order)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { s ->
            s.setObject(1, id); s.setObject(2, owner); s.setString(3, parent); s.setString(4, input.name)
            s.setString(5, CategoryInputPolicy.normalizedName(input.name)); s.setString(6, input.description); s.setArray(7, examples)
            s.setBoolean(8, eligible); s.setString(9, reason); s.setInt(10, order); check(s.executeUpdate()==1)
        } } finally { examples.free() }
        return id
    }

    fun update(connection: Connection, owner: UUID, id: UUID, input: CategoryInput, eligible: Boolean, reason: String?) {
        val examples = connection.createArrayOf("text", input.examples.toTypedArray())
        try { connection.prepareStatement("""update custom_categories set name = ?, normalized_name = ?, description = ?, examples = ?, ai_eligible = ?, ai_exclusion_reason = ?,
            version = version+1, updated_at = clock_timestamp() where owner_id = ? and id = ?""").use { s ->
            s.setString(1, input.name); s.setString(2, CategoryInputPolicy.normalizedName(input.name)); s.setString(3, input.description); s.setArray(4, examples)
            s.setBoolean(5, eligible); s.setString(6, reason); s.setObject(7, owner); s.setObject(8, id); check(s.executeUpdate()==1)
        } } finally { examples.free() }
    }

    private fun row(r: java.sql.ResultSet): CustomCategory {
        val examples = r.getArray("examples")
        val values = try { (examples.array as Array<*>).map { it as String } } finally { examples.free() }
        return CustomCategory(r.getObject("id", UUID::class.java), r.getString("parent_id"),
            CategoryInput(r.getString("name"), r.getString("description"), values), r.getInt("version"), r.getLong("item_count"),
            r.getTimestamp("created_at").toInstant(), r.getBoolean("ai_eligible"), r.getInt("display_order"))
    }
}
