package app.wishlist

import java.sql.Connection
import java.time.OffsetDateTime
import java.time.Instant
import java.time.ZoneOffset
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

class WishlistItemRepository(private val dataSource: DataSource) {
    fun findOwned(ownerId: UUID, itemId: UUID): WishlistItem? =
        dataSource.connection.use { connection ->
            findOwned(connection, ownerId, itemId)
        }

    internal fun findOwned(connection: Connection, ownerId: UUID, itemId: UUID): WishlistItem? =
        find(connection, ownerId, "id", itemId)

    internal fun findBySubmission(connection: Connection, ownerId: UUID, key: UUID): WishlistItem? =
        find(connection, ownerId, "client_submission_id", key)

    private fun find(connection: Connection, ownerId: UUID, keyColumn: String, key: UUID): WishlistItem? =
        connection.prepareStatement("""
            select id, owner_id, version, current_generation, analysis_status, review_status,
                   lifecycle_status, product_name, coalesce(category_id,custom_category_id::text) category_id, category_missing_reason,
                   manual_completion_at, category_source, purpose_id, purpose_source,
                   name_source, image_source, user_override_fields, client_submission_id, source_url,
                   product_image_url, analysis_failure_code, client_created_at, created_at, updated_at,
                   coalesce((select name from public_categories c where c.id=wishlist_items.category_id),
                     (select name from custom_categories c where c.id=wishlist_items.custom_category_id and c.owner_id=wishlist_items.owner_id and c.deleted_at is null)) category_name,
                   coalesce((select parent_id from public_categories c where c.id=wishlist_items.category_id),
                     (select parent_id from custom_categories c where c.id=wishlist_items.custom_category_id and c.owner_id=wishlist_items.owner_id and c.deleted_at is null)) category_parent,
                   case when custom_category_id is not null then 'CUSTOM' when category_id is not null then 'PUBLIC' end category_kind
            from wishlist_items where owner_id = ? and $keyColumn = ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, ownerId)
            statement.setObject(2, key)
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
                    clientCreatedAt = rows.getObject("client_created_at", OffsetDateTime::class.java)?.toInstant(),
                    createdAt = rows.getTimestamp("created_at").toInstant(),
                    updatedAt = rows.getTimestamp("updated_at").toInstant(),
                    categoryName=rows.getString("category_name"),categoryParentId=rows.getString("category_parent"),categoryKind=rows.getString("category_kind"),
                )
            }
        }

    internal fun insertItem(connection: Connection, itemId: UUID, ownerId: UUID, key: UUID, sourceUrl: String, clientCreatedAt: Instant?): Boolean =
        connection.prepareStatement("""
            insert into wishlist_items (id, owner_id, client_submission_id, source_url, client_created_at,
                analysis_status, lifecycle_status, current_generation, category_missing_reason)
            values (?, ?, ?, ?, ?, 'PROCESSING', 'ACTIVE', 1, 'EXTRACTION_UNRESOLVED')
            on conflict (owner_id, client_submission_id) do nothing
        """.trimIndent()).use { statement ->
            statement.setObject(1, itemId)
            statement.setObject(2, ownerId)
            statement.setObject(3, key)
            statement.setString(4, sourceUrl)
            statement.setObject(5, clientCreatedAt?.atOffset(ZoneOffset.UTC))
            statement.executeUpdate() == 1
        }

    internal fun insertInitialAnalysis(connection: Connection, itemId: UUID): UUID {
        val jobId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        connection.prepareStatement(
            "insert into analysis_jobs (id, wishlist_item_id, generation, stage) values (?, ?, 1, 'GENERAL_PENDING')",
        ).use { statement ->
            statement.setObject(1, jobId)
            statement.setObject(2, itemId)
            check(statement.executeUpdate() == 1)
        }
        connection.prepareStatement(
            "insert into outbox_events (id, analysis_job_id, event_type, task_name) values (?, ?, 'GENERAL_ANALYSIS', ?)",
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, jobId)
            statement.setString(3, "analysis-$jobId-1")
            check(statement.executeUpdate() == 1)
        }
        return eventId
    }
}
