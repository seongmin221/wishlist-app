package app.wishlist

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class StoredWishlistItemState(
    val id: UUID,
    val ownerId: UUID,
    val version: Int,
    val currentGeneration: Int,
    val state: WishlistItemState,
    val categorySource: ValueSource?,
    val purposeId: String?,
    val purposeSource: ValueSource,
    val nameSource: ValueSource?,
    val imageSource: ValueSource?,
    val userOverrideFields: Set<String>,
)

class WishlistItemStateRepository(private val dataSource: DataSource) {
    fun findOwned(ownerId: UUID, itemId: UUID): StoredWishlistItemState? =
        findViewOwned(ownerId, itemId)?.storedState

    fun findViewOwned(ownerId: UUID, itemId: UUID): WishlistItem? =
        dataSource.connection.use { connection ->
            findViewOwned(connection, ownerId, itemId)
        }

    internal fun findViewOwned(connection: Connection, ownerId: UUID, itemId: UUID): WishlistItem? =
        connection.prepareStatement("""
            select id, owner_id, version, current_generation, analysis_status, review_status,
                   lifecycle_status, product_name, category_id, category_missing_reason,
                   manual_completion_at, category_source, purpose_id, purpose_source,
                   name_source, image_source, user_override_fields, client_submission_id, source_url,
                   product_image_url, analysis_failure_code, client_created_at, created_at, updated_at
            from wishlist_items where owner_id = ? and id = ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, ownerId)
            statement.setObject(2, itemId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                val overrides = rows.getArray("user_override_fields")
                val overrideFields = try {
                    (overrides.array as Array<*>).map { it as String }.toSet()
                } finally {
                    overrides.free()
                }
                val storedState = StoredWishlistItemState(
                    id = rows.getObject("id", UUID::class.java),
                    ownerId = rows.getObject("owner_id", UUID::class.java),
                    version = rows.getInt("version"),
                    currentGeneration = rows.getInt("current_generation"),
                    state = WishlistItemState(
                        analysisStatus = AnalysisStatus.valueOf(rows.getString("analysis_status")),
                        reviewStatus = ReviewStatus.valueOf(rows.getString("review_status")),
                        lifecycleStatus = LifecycleStatus.valueOf(rows.getString("lifecycle_status")),
                        productName = rows.getString("product_name"),
                        categoryId = rows.getString("category_id"),
                        categoryMissingReason = rows.getString("category_missing_reason")?.let(CategoryMissingReason::valueOf),
                        manualCompletionAt = rows.getTimestamp("manual_completion_at")?.toInstant(),
                    ),
                    categorySource = rows.getString("category_source")?.let(ValueSource::valueOf),
                    purposeId = rows.getString("purpose_id"),
                    purposeSource = ValueSource.valueOf(rows.getString("purpose_source")),
                    nameSource = rows.getString("name_source")?.let(ValueSource::valueOf),
                    imageSource = rows.getString("image_source")?.let(ValueSource::valueOf),
                    userOverrideFields = overrideFields,
                )
                WishlistItem(
                    storedState = storedState,
                    clientSubmissionId = rows.getObject("client_submission_id", UUID::class.java),
                    sourceUrl = rows.getString("source_url"),
                    productImageUrl = rows.getString("product_image_url"),
                    analysisFailureCode = rows.getString("analysis_failure_code"),
                    clientCreatedAt = rows.getTimestamp("client_created_at")?.toInstant(),
                    createdAt = rows.getTimestamp("created_at").toInstant(),
                    updatedAt = rows.getTimestamp("updated_at").toInstant(),
                )
            }
        }
}
