package app.wishlist

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.net.InetAddress
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
                app.persistence.OwnerStructureLock.lock(connection,ownerId)
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
                logger.warn("Post-commit dispatch failed eventId={} exceptionType={}", result.outboxEventId, cause.javaClass.name)
            }
        }
        return result
    }

    private fun isPublicHttpUrl(sourceUrl: String): Boolean {
        if (sourceUrl.length > MAX_SOURCE_URL_LENGTH) return false
        // JDBC replaces unpaired surrogates, so the stored URL would stop matching its own key replay.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(sourceUrl)) return false
        return runCatching {
            URI(sourceUrl).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && !isLocalHost(it.host) }
        }.getOrDefault(false)
    }

    /** Cheap creation-time filter; extraction's UrlSafetyPolicy remains the network boundary. */
    private fun isLocalHost(rawHost: String): Boolean {
        // URI keeps the host's case and IPv6 brackets.
        val host = rawHost.lowercase().removeSurrounding("[", "]")
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }) {
            return octets[0].toInt() == 127 || octets.all { it.toInt() == 0 }
        }
        // A host with ':' is only parsed as an IPv6 literal, so this never reaches DNS.
        if (':' !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return address.isLoopbackAddress || address.isAnyLocalAddress
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CreateWishlistItemService::class.java)
    }
}
