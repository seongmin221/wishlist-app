package app.testutil

import java.util.UUID
import javax.sql.DataSource

/** One stored item for B7 tests; columns not listed keep their schema defaults. */
data class ItemSeed(
    val analysis: String = "READY",
    val review: String = "PENDING",
    val lifecycle: String = "ACTIVE",
    val name: String? = "AI 이름",
    val nameSource: String? = "AI",
    val brand: String? = "AI 브랜드",
    val category: String? = "C026",
    val customCategory: UUID? = null,
    val categorySource: String? = "AI",
    val missingReason: String? = null,
    val purpose: UUID? = null,
    val purposeSource: String = "UNASSIGNED",
    val media: UUID? = null,
    val imageUrl: String? = "https://shop.example/a.jpg",
    val imageSource: String? = "AI",
    val manualCompleted: Boolean = false,
    val failureCode: String? = null,
    val generation: Int = 1,
    val version: Int = 3,
)

fun seedItem(source: DataSource, owner: UUID, seed: ItemSeed = ItemSeed()): UUID {
    val id = UUID.randomUUID()
    analysisSql(source, "insert into app_users(id) values ('$owner') on conflict do nothing")
    source.connection.use { c ->
        c.prepareStatement("""insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,review_status,
            lifecycle_status,product_name,name_source,product_brand,category_id,custom_category_id,category_source,category_missing_reason,
            purpose_id,purpose_source,media_asset_id,product_image_url,image_source,manual_completion_at,analysis_failure_code,
            current_generation,version)
            values (?,?,?,'https://shop.example/item',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,case when ? then clock_timestamp() end,?,?,?)""").use { s ->
            var i = 1
            s.setObject(i++, id); s.setObject(i++, owner); s.setObject(i++, UUID.randomUUID())
            s.setString(i++, seed.analysis); s.setString(i++, seed.review); s.setString(i++, seed.lifecycle)
            s.setString(i++, seed.name); s.setString(i++, seed.nameSource); s.setString(i++, seed.brand)
            s.setString(i++, if (seed.customCategory == null) seed.category else null); s.setObject(i++, seed.customCategory)
            s.setString(i++, seed.categorySource); s.setString(i++, seed.missingReason)
            s.setObject(i++, seed.purpose); s.setString(i++, seed.purposeSource); s.setObject(i++, seed.media)
            s.setString(i++, seed.imageUrl); s.setString(i++, seed.imageSource); s.setBoolean(i++, seed.manualCompleted)
            s.setString(i++, seed.failureCode); s.setInt(i++, seed.generation); s.setInt(i, seed.version)
            check(s.executeUpdate() == 1)
        }
    }
    if (seed.media != null) analysisSql(source, "update media_assets set unreferenced_since=null where id='${seed.media}'")
    return id
}

fun insertCustomCategory(source: DataSource, owner: UUID, deleted: Boolean = false): UUID {
    val id = UUID.randomUUID()
    analysisSql(source, "insert into app_users(id) values ('$owner') on conflict do nothing")
    analysisSql(source, """insert into custom_categories(id,owner_id,parent_id,name,normalized_name,display_order,deleted_at)
        values ('$id','$owner','G003','내 분류 ${id.toString().take(8)}','내 분류 ${id.toString().take(8)}',
        (select coalesce(max(display_order),0)+1 from custom_categories where owner_id='$owner'),
        ${if (deleted) "clock_timestamp()" else "null"})""")
    return id
}

/** Selected columns of one item as text for exact before/after comparisons. */
fun itemColumns(source: DataSource, id: UUID, vararg columns: String): Map<String, String?> = source.connection.use { c ->
    c.prepareStatement("select ${columns.joinToString(",") { "$it::text" }} from wishlist_items where id=?").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { r -> check(r.next()); columns.withIndex().associate { (i, name) -> name to r.getString(i + 1) } }
    }
}

fun purposeColumns(source: DataSource, id: UUID): Map<String, String?> = source.connection.use { c ->
    c.prepareStatement("select membership_version::text, activity_at::text, activity_kind from purposes where id=?").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { r -> check(r.next()); mapOf("membership" to r.getString(1), "activity" to r.getString(2), "kind" to r.getString(3)) }
    }
}

fun mediaColumns(source: DataSource, id: UUID): Map<String, String?> = source.connection.use { c ->
    c.prepareStatement("select status, unreferenced_since::text, updated_at::text from media_assets where id=?").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { r -> check(r.next()); mapOf("status" to r.getString(1), "unreferenced" to r.getString(2), "updated" to r.getString(3)) }
    }
}
