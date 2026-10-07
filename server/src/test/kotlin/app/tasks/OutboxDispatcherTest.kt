package app.tasks

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import app.testutil.PostgresTestContainer
import app.testutil.createdItemId
import org.testcontainers.containers.PostgreSQLContainer

class OutboxDispatcherTest {
    @Test fun `specified publication skips missing leased and already published events`() = app.testutil.withAnalysisDatabase { source ->
        CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
        val eventId = UUID.fromString(app.testutil.analysisScalar(source, "select id from outbox_events"))
        var sends = 0
        val dispatcher = OutboxDispatcher(source, TaskGateway { sends++ })
        kotlin.test.assertFalse(dispatcher.dispatchEvent(UUID.randomUUID()))
        app.testutil.analysisSql(source, "update outbox_events set lease_until=clock_timestamp()+interval '120 seconds'")
        kotlin.test.assertFalse(dispatcher.dispatchEvent(eventId))
        assertEquals(0, sends)
        app.testutil.analysisSql(source, "update outbox_events set lease_until=clock_timestamp()-interval '1 second'")
        kotlin.test.assertTrue(dispatcher.dispatchEvent(eventId))
        kotlin.test.assertFalse(dispatcher.dispatchEvent(eventId))
        assertEquals(1, sends)
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events where published_at is not null and lease_until is null"))
    }

    @Test fun `specified and batch dispatch cannot publish the same active lease concurrently`() = app.testutil.withAnalysisDatabase { source ->
        CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
        val eventId = UUID.fromString(app.testutil.analysisScalar(source, "select id from outbox_events"))
        val other = OutboxDispatcher(source, TaskGateway { error("active lease cannot be published twice") })
        val published = app.testutil.pausedAnalysisCall({ pause ->
            OutboxDispatcher(source, TaskGateway { pause() }).dispatchEvent(eventId)
        }, {
            kotlin.test.assertFalse(other.dispatchEvent(eventId))
            assertEquals(0, other.dispatchPending(1))
        })
        kotlin.test.assertTrue(published)
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events where published_at is not null and lease_until is null"))
    }

    @Test fun `creation publishes its own event ahead of an older retry backlog`() = app.testutil.withAnalysisDatabase { source ->
        val oldItem = CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/old").createdItemId
        app.testutil.analysisSql(source, "update outbox_events set created_at=clock_timestamp()-interval '1 hour'")
        val dispatcher = OutboxDispatcher(source, TaskGateway { })
        val service = CreateWishlistItemService(source) { eventId -> dispatcher.dispatchEvent(eventId) }
        val fresh = service.create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/new").createdItemId
        assertEquals("1", app.testutil.analysisScalar(source, """select count(*) from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id
            where j.wishlist_item_id='$fresh' and e.published_at is not null"""))
        assertEquals("1", app.testutil.analysisScalar(source, """select count(*) from outbox_events e join analysis_jobs j on j.id=e.analysis_job_id
            where j.wishlist_item_id='$oldItem' and e.published_at is null"""))
    }

    @Test fun `lease release failure keeps the publication failure as suppressed`() = app.testutil.withAnalysisDatabase { source ->
        CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
        val eventId = UUID.fromString(app.testutil.analysisScalar(source, "select id from outbox_events"))
        var connections = 0
        val unavailableAfterClaim = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                if (++connections > 1) throw java.sql.SQLException("pool unavailable")
                return source.connection
            }
        }
        val queueFailure = IllegalStateException("queue unavailable")
        val dispatcher = OutboxDispatcher(unavailableAfterClaim, TaskGateway { throw queueFailure })
        val thrown = kotlin.test.assertFailsWith<java.sql.SQLException> { dispatcher.dispatchEvent(eventId) }
        kotlin.test.assertTrue(queueFailure in thrown.suppressed)
    }

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
        val service = CreateWishlistItemService(source) { eventId -> dispatcher.dispatchEvent(eventId) }
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

            val eventId = UUID.fromString(app.testutil.analysisScalar(dataSource, "select id from outbox_events"))
            kotlin.test.assertFalse(dispatcher.dispatchEvent(eventId))
            assertNull(publishedAt(database))
            assertEquals("1", app.testutil.analysisScalar(dataSource, "select count(*) from outbox_events where lease_until is null"))
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
