package app

import app.testutil.PostgresTestContainer
import java.sql.SQLException
import kotlin.test.*

class DatabaseFactoryTest {
    @Test fun `creation returns pool connection before post commit outbox dispatch`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            DatabaseFactory.pooledDataSource(database.jdbcUrl, database.username, database.password, DatabasePoolConfig(1, 300)).use { pool ->
                val published = mutableListOf<String>()
                val dispatcher = app.tasks.OutboxDispatcher(pool, app.tasks.TaskGateway { published.add(it.name) })
                val owner = java.util.UUID.randomUUID()
                val key = java.util.UUID.randomUUID()
                val service = app.wishlist.CreateWishlistItemService(pool) { eventId -> dispatcher.dispatchEvent(eventId) }
                val created = service.create(owner, key, "https://example.com/item")
                assertIs<app.wishlist.CreateResult.Created>(created)
                assertEquals(1, published.size, "dispatch must borrow from the same max=1 pool after creation released it")
                assertIs<app.wishlist.CreateResult.Replayed>(service.create(owner, key, "https://example.com/item"))
                assertEquals(1, published.size)
                assertEquals("1", app.testutil.analysisScalar(pool, "select count(*) from outbox_events where published_at is not null"))
            }
        }
    }

    @Test fun `post commit dispatch cancellation propagates while durable item stays replayable`() = app.testutil.withAnalysisDatabase { source ->
        val owner = java.util.UUID.randomUUID()
        val key = java.util.UUID.randomUUID()
        val service = app.wishlist.CreateWishlistItemService(source) { throw kotlinx.coroutines.CancellationException("cancel dispatch") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { service.create(owner, key, "https://example.com/item") }
        assertIs<app.wishlist.CreateResult.Replayed>(service.create(owner, key, "https://example.com/item"))
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events where published_at is null"))
    }

    @Test fun `bounded pool times out then reuses returned connection and rejects after close`() {
        PostgresTestContainer().use { database ->
            database.start()
            val pool = DatabaseFactory.pooledDataSource(database.jdbcUrl, database.username, database.password, DatabasePoolConfig(1, 300))
            try {
                val first = pool.connection
                try {
                    val started = System.nanoTime()
                    assertFailsWith<SQLException> { pool.connection }
                    assertTrue((System.nanoTime() - started) / 1_000_000 >= 250)
                } finally { first.close() }
                pool.connection.use { c ->
                    c.createStatement().use { s -> s.executeQuery("select 1").use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)) } }
                }
            } finally { pool.close() }
            assertFailsWith<SQLException> { pool.connection }
        }
    }

    @Test fun `pool rejects invalid limits before connecting`() {
        for (size in listOf(0, -1)) assertFailsWith<IllegalArgumentException> { DatabasePoolConfig(size) }
        for (timeout in listOf(0L, 249L)) assertFailsWith<IllegalArgumentException> { DatabasePoolConfig(1, timeout) }
    }
}
