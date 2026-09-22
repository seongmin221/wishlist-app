package app.wishlist

import app.DatabaseFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.Executors

class CreateWishlistItemServiceTest {
    @Test
    fun `same owner and key returns original item without a second job`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val service = CreateWishlistItemService(
                DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password),
            )
            val ownerId = UUID.randomUUID()
            val key = UUID.randomUUID()

            val first = service.create(ownerId, key, "https://example.com/product")
            val replay = service.create(ownerId, key, "https://example.com/product")

            assertIs<CreateResult.Created>(first)
            assertIs<CreateResult.Replayed>(replay)
            assertEquals(first.itemId, replay.itemId)
            databaseCount(database, "wishlist_items").also { assertEquals(1, it) }
            databaseCount(database, "analysis_jobs").also { assertEquals(1, it) }
            databaseCount(database, "outbox_events").also { assertEquals(1, it) }
        }
    }

    @Test
    fun `same key with another url is rejected without new work`() = withDatabase { database, service ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        service.create(owner, key, "https://example.com/one")

        assertIs<CreateResult.IdempotencyKeyReused>(service.create(owner, key, "https://example.com/two"))
        assertEquals(1, databaseCount(database, "wishlist_items"))
        assertEquals(1, databaseCount(database, "analysis_jobs"))
        assertEquals(1, databaseCount(database, "outbox_events"))
    }

    @Test
    fun `concurrent creates leave one item job and event`() = withDatabase { database, service ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<CreateResult> { service.create(owner, key, "https://example.com/item") } }
            val results = futures.map { it.get() }
            assertEquals(1, results.count { it is CreateResult.Created })
            assertEquals(1, results.count { it is CreateResult.Replayed })
            assertEquals(1, results.map { it.itemId }.distinct().size)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, databaseCount(database, "wishlist_items"))
        assertEquals(1, databaseCount(database, "analysis_jobs"))
        assertEquals(1, databaseCount(database, "outbox_events"))
    }

    @Test
    fun `unsafe url is rejected before any database write`() = withDatabase { database, service ->
        assertIs<CreateResult.InvalidUrl>(service.create(UUID.randomUUID(), UUID.randomUUID(), "http://127.0.0.1/private"))
        assertEquals(0, databaseCount(database, "wishlist_items"))
    }

    private fun withDatabase(block: (PostgreSQLContainer<*>, CreateWishlistItemService) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            block(database, CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)))
        }
    }

    private fun databaseCount(database: PostgreSQLContainer<*>, table: String): Int =
        database.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from $table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}
