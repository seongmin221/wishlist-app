package app.wishlist

import kotlinx.coroutines.CancellationException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

sealed interface CreateResult {
    /** Outcomes backed by a stored item; only these expose an item ID. */
    sealed interface Stored : CreateResult {
        val item: WishlistItem
        val itemId: UUID get() = item.id
    }

    data class Created(override val item: WishlistItem, val outboxEventId: UUID) : Stored
    data class Replayed(override val item: WishlistItem) : Stored
    data class IdempotencyKeyReused(override val item: WishlistItem) : Stored
    data object InvalidUrl : CreateResult
}

private const val MAX_SOURCE_URL_LENGTH = 2048

class CreateWishlistItemService(
    private val dataSource: DataSource,
    private val dispatchAfterCommit: (UUID) -> Unit = {},
) {
    private val items = WishlistItemRepository(dataSource)

    fun create(ownerId: UUID, key: UUID, sourceUrl: String, clientCreatedAt: Instant? = null): CreateResult {
        if (!isPublicHttpUrl(sourceUrl)) return CreateResult.InvalidUrl
        val result = dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val itemId = UUID.randomUUID()
                val result = if (items.insertItem(connection, itemId, ownerId, key, sourceUrl, clientCreatedAt)) {
                    val eventId = items.insertInitialAnalysis(connection, itemId)
                    CreateResult.Created(checkNotNull(items.findOwned(connection, ownerId, itemId)), eventId)
                } else {
                    val item = checkNotNull(items.findBySubmission(connection, ownerId, key)) { "conflicting wishlist item missing" }
                    if (item.sourceUrl == sourceUrl) CreateResult.Replayed(item) else CreateResult.IdempotencyKeyReused(item)
                }
                connection.commit()
                result
            } catch (cause: Exception) {
                connection.rollback()
                throw cause
            }
        }
        if (result is CreateResult.Created) {
            try { dispatchAfterCommit(result.outboxEventId) } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                // The committed item snapshot and durable outbox remain valid even if publication fails.
            }
        }
        return result
    }

    private fun isPublicHttpUrl(sourceUrl: String): Boolean {
        if (sourceUrl.length > MAX_SOURCE_URL_LENGTH) return false
        // JDBC replaces unpaired surrogates, so the stored URL would stop matching its own key replay.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(sourceUrl)) return false
        return runCatching {
            URI(sourceUrl).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.host !in setOf("localhost", "127.0.0.1", "::1") }
        }.getOrDefault(false)
    }
}
