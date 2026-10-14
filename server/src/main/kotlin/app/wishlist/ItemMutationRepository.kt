package app.wishlist

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** A stored item as B7 commands see it: the editable row plus the state used for allowed actions. */
data class MutableItem(
    val id: UUID,
    val version: Int,
    val generation: Int,
    val lifecycle: LifecycleStatus,
    val state: WishlistItemState,
    val row: ItemRow,
)

/** SQL for B7 commands. Every function expects the caller's transaction and the owner structure lock. */
internal object ItemMutationRepository {
    private const val COLUMNS = """id, version, current_generation, lifecycle_status, analysis_status, review_status, product_name,
        name_source, product_brand, category_id, custom_category_id, category_source, category_missing_reason, manual_completion_at,
        purpose_id, purpose_source, media_asset_id, image_source, product_image_url, user_override_fields"""

    fun find(c: Connection, owner: UUID, itemId: UUID, lock: Boolean): MutableItem? =
        c.prepareStatement("select $COLUMNS from wishlist_items where owner_id=? and id=?${if (lock) " for update" else ""}").use { s ->
            s.setObject(1, owner); s.setObject(2, itemId)
            s.executeQuery().use { r -> if (r.next()) map(r) else null }
        }

    /** Locks the owner's purposes in ID order whatever their lifecycle; availability is judged after the item lock. */
    fun lockPurposes(c: Connection, owner: UUID, ids: Collection<UUID>) {
        val sorted = ids.distinct().sorted()
        if (sorted.isEmpty()) return
        c.prepareStatement("select id from purposes where owner_id=? and id = any(?) order by id for update").use { s ->
            s.setObject(1, owner); s.setArray(2, c.createArrayOf("uuid", sorted.toTypedArray()))
            s.executeQuery().use { r -> while (r.next()) Unit }
        }
    }

    fun purposeActive(c: Connection, owner: UUID, id: UUID): Boolean =
        c.prepareStatement("select 1 from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'").use { s ->
            s.setObject(1, owner); s.setObject(2, id); s.executeQuery().use { it.next() }
        }

    fun customCategoryAvailable(c: Connection, owner: UUID, id: UUID): Boolean =
        c.prepareStatement("select 1 from custom_categories where owner_id=? and id=? and deleted_at is null").use { s ->
            s.setObject(1, owner); s.setObject(2, id); s.executeQuery().use { it.next() }
        }

    /** Media rows in ID order with FOR UPDATE (B6 handoff); returns each found row's status. */
    fun lockMedia(c: Connection, owner: UUID, ids: Collection<UUID>): Map<UUID, String> {
        val sorted = ids.distinct().sorted()
        if (sorted.isEmpty()) return emptyMap()
        return c.prepareStatement("select id, status from media_assets where owner_id=? and id = any(?) order by id for update").use { s ->
            s.setObject(1, owner); s.setArray(2, c.createArrayOf("uuid", sorted.toTypedArray()))
            s.executeQuery().use { r -> buildMap { while (r.next()) put(r.getObject(1, UUID::class.java), r.getString(2)) } }
        }
    }

    /** Separate statement after the media lock so a reference committed while waiting is seen. */
    fun mediaUsedByOther(c: Connection, mediaId: UUID, itemId: UUID): Boolean =
        c.prepareStatement("select 1 from wishlist_items where media_asset_id=? and id<>?").use { s ->
            s.setObject(1, mediaId); s.setObject(2, itemId); s.executeQuery().use { it.next() }
        }

    fun linkMedia(c: Connection, mediaId: UUID) = c.prepareStatement(
        "update media_assets set unreferenced_since=null, updated_at=clock_timestamp() where id=? and status='READY'",
    ).use { s -> s.setObject(1, mediaId); check(s.executeUpdate() == 1) }

    /** Starts the cleanup grace of a released READY media; rows already cleaned up stay as they are. */
    fun releaseMedia(c: Connection, mediaId: UUID) = c.prepareStatement(
        "update media_assets set unreferenced_since=clock_timestamp(), updated_at=clock_timestamp() where id=? and status='READY'",
    ).use { s -> s.setObject(1, mediaId); s.executeUpdate() }

    /** One UPDATE for the planned row; version and updated_at change once. */
    fun write(c: Connection, itemId: UUID, row: ItemRow, completeManually: Boolean) = c.prepareStatement("""
        update wishlist_items set product_name=?, name_source=?, product_brand=?, category_id=?, custom_category_id=?,
            category_source=?, category_missing_reason=?, purpose_id=?, purpose_source=?, media_asset_id=?, image_source=?,
            user_override_fields=?, review_status=?,
            manual_completion_at=case when ? then clock_timestamp() else manual_completion_at end,
            version=version+1, updated_at=clock_timestamp()
        where id=?
    """.trimIndent()).use { s ->
        var i = 1
        s.setString(i++, row.name); s.setString(i++, row.nameSource?.name); s.setString(i++, row.brand)
        s.setString(i++, row.publicCategoryId); s.setObject(i++, row.customCategoryId); s.setString(i++, row.categorySource?.name)
        s.setString(i++, row.categoryMissingReason?.name); s.setObject(i++, row.purposeId); s.setString(i++, row.purposeSource.name)
        s.setObject(i++, row.mediaId); s.setString(i++, row.imageSource?.name)
        s.setArray(i++, c.createArrayOf("text", row.overrides.sorted().toTypedArray())); s.setString(i++, row.reviewStatus.name)
        s.setBoolean(i++, completeManually); s.setObject(i, itemId)
        check(s.executeUpdate() == 1)
    }

    private fun map(r: ResultSet): MutableItem {
        val overrides = r.getArray("user_override_fields").let { array ->
            try { (array.array as Array<*>).map { it as String }.toSet() } finally { array.free() }
        }
        val publicCategory = r.getString("category_id")
        val customCategory = r.getObject("custom_category_id", UUID::class.java)
        val missingReason = r.getString("category_missing_reason")?.let(CategoryMissingReason::valueOf)
        val review = ReviewStatus.valueOf(r.getString("review_status"))
        val name = r.getString("product_name")
        return MutableItem(
            id = r.getObject("id", UUID::class.java),
            version = r.getInt("version"),
            generation = r.getInt("current_generation"),
            lifecycle = LifecycleStatus.valueOf(r.getString("lifecycle_status")),
            state = WishlistItemState(
                analysisStatus = AnalysisStatus.valueOf(r.getString("analysis_status")), reviewStatus = review,
                lifecycleStatus = LifecycleStatus.valueOf(r.getString("lifecycle_status")), productName = name,
                categoryId = publicCategory ?: customCategory?.toString(), categoryMissingReason = missingReason,
                manualCompletionAt = r.getTimestamp("manual_completion_at")?.toInstant(),
            ),
            row = ItemRow(
                name = name, nameSource = r.getString("name_source")?.let(ValueSource::valueOf), brand = r.getString("product_brand"),
                publicCategoryId = publicCategory, customCategoryId = customCategory,
                categorySource = r.getString("category_source")?.let(ValueSource::valueOf), categoryMissingReason = missingReason,
                purposeId = r.getObject("purpose_id", UUID::class.java), purposeSource = ValueSource.valueOf(r.getString("purpose_source")),
                mediaId = r.getObject("media_asset_id", UUID::class.java), imageSource = r.getString("image_source")?.let(ValueSource::valueOf),
                productImageUrl = r.getString("product_image_url"), overrides = overrides, reviewStatus = review,
            ),
        )
    }
}
