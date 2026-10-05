package app.tasks

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import app.testutil.PostgresTestContainer
import org.testcontainers.containers.PostgreSQLContainer

class OutboxDispatcherTest {
    @Test fun `gateway cancellation propagates through creation after releasing outbox lease`() = app.testutil.withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val key = UUID.randomUUID()
        val dispatcher = OutboxDispatcher(source, TaskGateway { throw kotlinx.coroutines.CancellationException("cancel task request") })
        val service = CreateWishlistItemService(source) { dispatcher.dispatchPending(1) }
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { service.create(owner, key, "https://example.com/item") }
        kotlin.test.assertIs<app.wishlist.CreateResult.Replayed>(service.create(owner, key, "https://example.com/item"))
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events where published_at is null and lease_until is null"))
    }

    @Test
    fun `failed publication remains recoverable and keeps its task name`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
                .create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val dataSource = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val names = mutableListOf<String>()
            val dispatcher = OutboxDispatcher(dataSource, TaskGateway { task ->
                names += task.name
                if (names.size == 1) error("queue unavailable")
            })

            assertEquals(0, dispatcher.dispatchPending(1))
            assertNull(publishedAt(database))
            assertEquals(1, dispatcher.dispatchPending(1))
            assertNotNull(publishedAt(database))
            assertEquals(names[0], names[1])
        }
    }

    private fun publishedAt(database: PostgreSQLContainer<*>): Any? = database.createConnection("").use { connection ->
        connection.createStatement().executeQuery("select published_at from outbox_events").use { rows ->
            rows.next()
            rows.getTimestamp(1)
        }
    }
}
