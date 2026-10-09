package app.wishlist

import java.sql.Connection
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
            select ${WishlistItemRowMapper.columns} from wishlist_items i ${WishlistItemRowMapper.joins}
            where i.owner_id = ? and i.$keyColumn = ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, ownerId)
            statement.setObject(2, key)
            statement.executeQuery().use { rows -> if (rows.next()) WishlistItemRowMapper.map(rows) else null }
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
