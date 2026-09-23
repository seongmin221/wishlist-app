package app.analysis

import app.DatabaseFactory
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.testcontainers.containers.PostgreSQLContainer

class GeneralWorkerServiceTest {
    @Test
    fun `duplicate and deleted job are acknowledged without processing`() = withJob { database, jobId, itemId ->
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { ProcessingOutcome.Retryable }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 2))
        database.createConnection("").use { connection ->
            connection.prepareStatement("update wishlist_items set lifecycle_status='DELETED' where id=?").use {
                it.setObject(1, itemId)
                it.executeUpdate()
            }
        }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(0, attempts(database, jobId))
    }

    @Test
    fun `third retryable attempt becomes failed retryable`() = withJob { database, jobId, _ ->
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { ProcessingOutcome.Retryable }
        repeat(2) { assertEquals(WorkerDisposition.RETRY, worker.runGeneral(jobId, 1)) }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(3, attempts(database, jobId))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("FAILED_RETRYABLE", rows.getString(1))
            }
        }
    }

    @Test
    fun `expired running job is requeued with a new outbox task`() = withJob { database, jobId, _ ->
        database.createConnection("").use { connection ->
            connection.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING', attempt_count=1, first_attempt_at=now()-interval '2 minutes', updated_at=now()-interval '121 seconds' where id=?").use {
                it.setObject(1, jobId)
                it.executeUpdate()
            }
        }
        val reconciler = AnalysisJobReconciler(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))

        assertEquals(1, reconciler.reconcileExpired())
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select stage from analysis_jobs").use { rows ->
                rows.next()
                assertEquals("GENERAL_PENDING", rows.getString(1))
            }
            connection.createStatement().executeQuery("select count(*) from outbox_events").use { rows ->
                rows.next()
                assertEquals(2, rows.getInt(1))
            }
        }
    }

    @Test
    fun `expired thirty minute deadline prevents another attempt`() = withJob { database, jobId, _ ->
        database.createConnection("").use { connection ->
            connection.prepareStatement("update analysis_jobs set attempt_count=1, first_attempt_at=now()-interval '31 minutes' where id=?").use {
                it.setObject(1, jobId)
                it.executeUpdate()
            }
        }
        val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { error("must not run") }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        assertEquals(1, attempts(database, jobId))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("FAILED_RETRYABLE", rows.getString(1))
            }
        }
    }

    @Test
    fun `claim revoked during processing cannot mark item ready`() = withJob { database, jobId, _ ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        val worker = GeneralWorkerService(source) { id ->
            source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='CANCELLED' where id=?").use { s -> s.setObject(1,id); s.executeUpdate() } }
            ProcessingOutcome.Complete
        }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runGeneral(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select analysis_status from wishlist_items").use { r -> r.next(); assertEquals("PROCESSING", r.getString(1)) } }
    }

    private fun withJob(block: (PostgreSQLContainer<*>, UUID, UUID) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val item = CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
                .create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item") as CreateResult.Created
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
            block(database, jobId, item.itemId)
        }
    }

    private fun attempts(database: PostgreSQLContainer<*>, jobId: UUID): Int = database.createConnection("").use { connection ->
        connection.prepareStatement("select attempt_count from analysis_jobs where id=?").use {
            it.setObject(1, jobId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
