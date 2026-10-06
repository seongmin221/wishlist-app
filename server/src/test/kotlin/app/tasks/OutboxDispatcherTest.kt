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
    @Test fun `outbox lease uses the same database clock for reservation and discovery`() = app.testutil.withAnalysisDatabase { source ->
        app.testutil.analysisSql(source, "create schema shifted_clock")
        app.testutil.analysisSql(source, "create function shifted_clock.clock_timestamp() returns timestamptz language sql as 'select pg_catalog.clock_timestamp() + interval ''30 seconds''' ")
        val observed = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection = source.connection.also { c ->
                c.createStatement().use { it.execute("set search_path=shifted_clock,pg_catalog,public") }
            }
        }
        CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
        var remaining = 0.0
        val dispatcher = OutboxDispatcher(observed, TaskGateway {
            remaining = app.testutil.analysisScalar(observed, "select extract(epoch from lease_until-clock_timestamp()) from outbox_events")!!.toDouble()
            assertEquals(0, OutboxDispatcher(observed, TaskGateway { error("leased event cannot publish twice") }).dispatchPending(1))
        })
        assertEquals(1, dispatcher.dispatchPending(1))
        kotlin.test.assertTrue(remaining in 115.0..121.0, "remaining DB lease seconds: $remaining")
    }
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
