package app.wishlist

import kotlinx.coroutines.CancellationException
import java.net.URI
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

sealed interface CreateResult {
    val item: WishlistItem?
    val itemId: UUID get() = requireNotNull(item).id

    data class Created(override val item: WishlistItem, val outboxEventId: UUID) : CreateResult
    data class Replayed(override val item: WishlistItem) : CreateResult
    data class IdempotencyKeyReused(override val item: WishlistItem) : CreateResult
    data object InvalidUrl : CreateResult { override val item: WishlistItem? = null }
}

class CreateWishlistItemService(
    private val dataSource: DataSource,
    private val dispatchAfterCommit: (UUID) -> Unit = {},
) {
    internal val items = WishlistItemStateRepository(dataSource)

    fun create(ownerId: UUID, key: UUID, sourceUrl: String, clientCreatedAt: Instant? = null): CreateResult {
        if (!isPublicHttpUrl(sourceUrl)) return CreateResult.InvalidUrl

        val result = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val itemId = UUID.randomUUID()
                val jobId = UUID.randomUUID()
                if (!connection.insertWishlistItem(itemId, ownerId, key, sourceUrl, clientCreatedAt)) {
                    val existing = connection.prepareStatement(
                        "select id, source_url from wishlist_items where owner_id = ? and client_submission_id = ?",
                    ).use { statement ->
                        statement.setObject(1, ownerId)
                        statement.setObject(2, key)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "conflicting wishlist item missing" }
                            UUID.fromString(rows.getString("id")) to rows.getString("source_url")
                        }
                    }
                    val item = checkNotNull(items.findViewOwned(connection, ownerId, existing.first))
                    connection.commit()
                    return@use if (existing.second == sourceUrl) CreateResult.Replayed(item)
                    else CreateResult.IdempotencyKeyReused(item)
                }
                connection.prepareStatement(
                    "insert into analysis_jobs (id, wishlist_item_id, generation, stage) values (?, ?, 1, 'GENERAL_PENDING')",
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, itemId)
                    statement.executeUpdate()
                }
                val eventId = UUID.randomUUID()
                connection.prepareStatement(
                    "insert into outbox_events (id, analysis_job_id, event_type, task_name) values (?, ?, 'GENERAL_ANALYSIS', ?)",
                ).use { statement ->
                    statement.setObject(1, eventId)
                    statement.setObject(2, jobId)
                    statement.setString(3, "analysis-$jobId-1")
                    statement.executeUpdate()
                }
                val item = checkNotNull(items.findViewOwned(connection, ownerId, itemId))
                connection.commit()
                CreateResult.Created(item, eventId)
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
        if (result is CreateResult.Created) {
            try { dispatchAfterCommit(result.outboxEventId) } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                // The committed outbox remains available to the scheduled dispatcher.
            }
            return result.copy(item = checkNotNull(items.findViewOwned(ownerId, result.itemId)))
        }
        return result
    }

    private fun Connection.insertWishlistItem(itemId: UUID, ownerId: UUID, key: UUID, sourceUrl: String, clientCreatedAt: Instant?): Boolean {
        prepareStatement(
            "insert into wishlist_items (id, owner_id, client_submission_id, source_url, client_created_at, analysis_status, lifecycle_status, current_generation, category_missing_reason) values (?, ?, ?, ?, ?, 'PROCESSING', 'ACTIVE', 1, 'EXTRACTION_UNRESOLVED') on conflict (owner_id, client_submission_id) do nothing",
        ).use { statement ->
            statement.setObject(1, itemId)
            statement.setObject(2, ownerId)
            statement.setObject(3, key)
            statement.setString(4, sourceUrl)
            statement.setTimestamp(5, clientCreatedAt?.let(Timestamp::from))
            return statement.executeUpdate() == 1
        }
    }

    private fun isPublicHttpUrl(sourceUrl: String): Boolean = runCatching {
        URI(sourceUrl).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.host !in setOf("localhost", "127.0.0.1", "::1") }
    }.getOrDefault(false)
}
