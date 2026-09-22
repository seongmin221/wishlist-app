package app.tasks

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.testcontainers.containers.PostgreSQLContainer

class OutboxDispatcherTest {
    @Test
    fun `failed publication remains recoverable and keeps its task name`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
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
