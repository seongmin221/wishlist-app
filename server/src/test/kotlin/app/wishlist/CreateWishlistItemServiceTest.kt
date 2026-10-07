package app.wishlist

import app.DatabaseFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import app.testutil.PostgresTestContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.Executors

class CreateWishlistItemServiceTest {
    @Test fun `committed creation returns its snapshot even if further database connections fail`() = app.testutil.withAnalysisDatabase { source ->
        var committed = false
        val observed = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                if (committed) throw java.sql.SQLException("pool unavailable after commit")
                return source.connection
            }
        }
        val service = CreateWishlistItemService(observed) { committed = true }
        val created = assertIs<CreateResult.Created>(service.create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item"))
        assertEquals(AnalysisStatus.PROCESSING, created.item.storedState.state.analysisStatus)
        assertEquals(1, created.item.version)
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from wishlist_items"))
        assertEquals("1", app.testutil.analysisScalar(source, "select count(*) from outbox_events"))
    }

    @Test fun `sharing time before Gregorian cutover is stored without a calendar shift`() = app.testutil.withAnalysisDatabase { source ->
        val time = java.time.Instant.parse("1500-01-02T10:00:00Z")
        val created = assertIs<CreateResult.Created>(CreateWishlistItemService(source).create(
            UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item", time,
        ))
        assertEquals(time, created.item.clientCreatedAt)
        assertEquals("1500-01-02 10:00:00", app.testutil.analysisScalar(source,
            "select (client_created_at at time zone 'UTC')::text from wishlist_items where id='${created.itemId}'"))
    }

    @Test
    fun `same owner and key returns original item without a second job`() {
        PostgresTestContainer().use { database ->
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
            assertEquals(1, results.map { assertIs<CreateResult.Stored>(it).itemId }.distinct().size)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, databaseCount(database, "wishlist_items"))
        assertEquals(1, databaseCount(database, "analysis_jobs"))
        assertEquals(1, databaseCount(database, "outbox_events"))
    }

    @Test
    fun `unsafe url is rejected before any database write`() = withDatabase { database, service ->
        for (url in listOf("http://127.0.0.1/private", "http://127.0.0.2/private", "http://0.0.0.0/private",
            "http://LOCALHOST/private", "http://shop.localhost/private", "http://[::1]/private",
            "http://[0:0:0:0:0:0:0:1]/private", "http://[::ffff:127.0.0.1]/private", "http://[::]/private")) {
            assertIs<CreateResult.InvalidUrl>(service.create(UUID.randomUUID(), UUID.randomUUID(), url), url)
        }
        assertEquals(0, databaseCount(database, "wishlist_items"))
        // Public literals and DNS names that merely resemble loopback are left to extraction's network policy.
        for (url in listOf("http://128.0.0.1/item", "http://127.example.com/item", "http://[2001:db8::1]/item")) {
            assertIs<CreateResult.Created>(service.create(UUID.randomUUID(), UUID.randomUUID(), url), url)
        }
    }

    @Test
    fun `unencodable and overlong urls are rejected before any database write`() = withDatabase { database, service ->
        val base = "https://example.com/"
        for (url in listOf("${base}a\uD800b", "${base}a\uDC00", base + "a".repeat(2049 - base.length))) {
            assertIs<CreateResult.InvalidUrl>(service.create(UUID.randomUUID(), UUID.randomUUID(), url), url.take(40))
        }
        assertEquals(0, databaseCount(database, "wishlist_items"))
    }

    @Test
    fun `boundary length and surrogate pair urls round trip for key replay`() = withDatabase { _, service ->
        val base = "https://example.com/"
        for (url in listOf(base + "a".repeat(2048 - base.length), "${base}😀")) {
            val owner = UUID.randomUUID()
            val key = UUID.randomUUID()
            assertIs<CreateResult.Created>(service.create(owner, key, url))
            assertIs<CreateResult.Replayed>(service.create(owner, key, url))
        }
    }

    @Test
    fun `queue publication failure after commit does not lose accepted item`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            var attempted = 0
            val service = CreateWishlistItemService(
                DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password),
            ) { attempted++; error("queue unavailable") }

            assertIs<CreateResult.Created>(service.create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item"))
            assertEquals(1, attempted)
            assertEquals(1, databaseCount(database, "wishlist_items"))
            assertEquals(1, databaseCount(database, "outbox_events"))
        }
    }

    private fun withDatabase(block: (PostgreSQLContainer<*>, CreateWishlistItemService) -> Unit) {
        PostgresTestContainer().use { database ->
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
