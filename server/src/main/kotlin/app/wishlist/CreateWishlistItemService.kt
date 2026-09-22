package app.wishlist

import java.net.URI
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

sealed interface CreateResult {
    val item: WishlistItem?
    val itemId: UUID get() = requireNotNull(item).id

    data class Created(override val item: WishlistItem) : CreateResult
    data class Replayed(override val item: WishlistItem) : CreateResult
    data class IdempotencyKeyReused(override val item: WishlistItem) : CreateResult
    data object InvalidUrl : CreateResult { override val item: WishlistItem? = null }
}

class CreateWishlistItemService(private val dataSource: DataSource) {
    fun create(ownerId: UUID, key: UUID, sourceUrl: String): CreateResult {
        if (!isPublicHttpUrl(sourceUrl)) return CreateResult.InvalidUrl

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val itemId = UUID.randomUUID()
                val jobId = UUID.randomUUID()
                if (!connection.insertWishlistItem(itemId, ownerId, key, sourceUrl)) {
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
                    connection.commit()
                    val item = connection.loadItem(existing.first)
                    return if (existing.second == sourceUrl) CreateResult.Replayed(item)
                    else CreateResult.IdempotencyKeyReused(item)
                }
                connection.prepareStatement(
                    "insert into analysis_jobs (id, wishlist_item_id, generation, stage) values (?, ?, 1, 'GENERAL_PENDING')",
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, itemId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "insert into outbox_events (id, analysis_job_id, event_type, task_name) values (?, ?, 'GENERAL_ANALYSIS', ?)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, jobId)
                    statement.setString(3, "analysis-$jobId-1")
                    statement.executeUpdate()
                }
                connection.commit()
                return CreateResult.Created(connection.loadItem(itemId))
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun Connection.loadItem(itemId: UUID): WishlistItem = prepareStatement(
        "select id, client_submission_id, source_url, version, analysis_status, lifecycle_status, created_at, updated_at from wishlist_items where id = ?",
    ).use { statement ->
        statement.setObject(1, itemId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "wishlist item missing" }
            WishlistItem(
                id = rows.getObject("id", UUID::class.java),
                clientSubmissionId = rows.getObject("client_submission_id", UUID::class.java),
                sourceUrl = rows.getString("source_url"),
                version = rows.getInt("version"),
                analysisStatus = rows.getString("analysis_status"),
                lifecycleStatus = rows.getString("lifecycle_status"),
                createdAt = rows.getTimestamp("created_at").toInstant(),
                updatedAt = rows.getTimestamp("updated_at").toInstant(),
            )
        }
    }

    private fun Connection.insertWishlistItem(itemId: UUID, ownerId: UUID, key: UUID, sourceUrl: String): Boolean {
        prepareStatement(
            "insert into wishlist_items (id, owner_id, client_submission_id, source_url, analysis_status, lifecycle_status) values (?, ?, ?, ?, 'PROCESSING', 'ACTIVE') on conflict (owner_id, client_submission_id) do nothing",
        ).use { statement ->
            statement.setObject(1, itemId)
            statement.setObject(2, ownerId)
            statement.setObject(3, key)
            statement.setString(4, sourceUrl)
            return statement.executeUpdate() == 1
        }
    }

    private fun isPublicHttpUrl(sourceUrl: String): Boolean = runCatching {
        URI(sourceUrl).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.host !in setOf("localhost", "127.0.0.1", "::1") }
    }.getOrDefault(false)
}
