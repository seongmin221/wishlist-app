package app.wishlist

import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Shared projection for detail, replay and B4 cards. */
object WishlistItemRowMapper {
    val columns = """i.id, i.owner_id, i.version, i.current_generation, i.analysis_status, i.review_status,
       i.lifecycle_status, i.product_name, coalesce(i.category_id,i.custom_category_id::text) category_id, i.category_missing_reason,
       i.manual_completion_at, i.category_source, i.purpose_id::text purpose_id, i.purpose_source,
       i.name_source, i.image_source, i.user_override_fields, i.client_submission_id, i.source_url,
       i.product_image_url, i.analysis_failure_code, i.client_created_at, i.created_at, i.updated_at,
       coalesce(pc.name, cc.name) category_name, coalesce(pc.parent_id, cc.parent_id) category_parent,
       case when i.custom_category_id is not null then 'CUSTOM' when i.category_id is not null then 'PUBLIC' end category_kind,
       p.name purpose_name, p.color_key purpose_color_key, p.icon_key purpose_icon_key"""
    val joins = """left join public_categories pc on pc.id=i.category_id
left join custom_categories cc on cc.owner_id=i.owner_id and cc.id=i.custom_category_id and cc.deleted_at is null
left join purposes p on p.owner_id=i.owner_id and p.id=i.purpose_id"""

    fun map(row: ResultSet): WishlistItem {
        val overrides = row.getArray("user_override_fields")
        val overrideFields = try {
            (overrides.array as Array<*>).map { it as String }.toSet()
        } finally {
            overrides.free()
        }
        val storedState = StoredWishlistItemState(
            id = row.getObject("id", UUID::class.java),
            ownerId = row.getObject("owner_id", UUID::class.java),
            version = row.getInt("version"),
            currentGeneration = row.getInt("current_generation"),
            state = WishlistItemState(
                analysisStatus = AnalysisStatus.valueOf(row.getString("analysis_status")),
                reviewStatus = ReviewStatus.valueOf(row.getString("review_status")),
                lifecycleStatus = LifecycleStatus.valueOf(row.getString("lifecycle_status")),
                productName = row.getString("product_name"),
                categoryId = row.getString("category_id"),
                categoryMissingReason = row.getString("category_missing_reason")?.let(CategoryMissingReason::valueOf),
                manualCompletionAt = row.getTimestamp("manual_completion_at")?.toInstant(),
            ),
            categorySource = row.getString("category_source")?.let(ValueSource::valueOf),
            purposeId = row.getString("purpose_id"),
            purposeSource = ValueSource.valueOf(row.getString("purpose_source")),
            nameSource = row.getString("name_source")?.let(ValueSource::valueOf),
            imageSource = row.getString("image_source")?.let(ValueSource::valueOf),
            userOverrideFields = overrideFields,
        )
        return WishlistItem(
            storedState = storedState,
            clientSubmissionId = row.getObject("client_submission_id", UUID::class.java),
            sourceUrl = row.getString("source_url"),
            productImageUrl = row.getString("product_image_url"),
            analysisFailureCode = row.getString("analysis_failure_code"),
            clientCreatedAt = row.getObject("client_created_at", OffsetDateTime::class.java)?.toInstant(),
            createdAt = row.getTimestamp("created_at").toInstant(),
            updatedAt = row.getTimestamp("updated_at").toInstant(),
            categoryName=row.getString("category_name"),categoryParentId=row.getString("category_parent"),categoryKind=row.getString("category_kind"),
            purposeName = row.getString("purpose_name"), purposeColorKey = row.getString("purpose_color_key"),
            purposeIconKey = row.getString("purpose_icon_key"),
        )
    }
}
