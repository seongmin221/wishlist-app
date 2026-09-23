package app.browser

import app.DatabaseFactory
import app.analysis.GeneralWorkerService
import app.analysis.ProcessingOutcome
import app.analysis.WorkerDisposition
import app.wishlist.CreateWishlistItemService
import app.extraction.Metadata
import app.extraction.UnsafeUrlException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import app.analysis.AnalysisJobReconciler
import org.testcontainers.containers.PostgreSQLContainer

class BrowserWorkerServiceTest {
    @Test
    fun `needs browser stores stage flag and outbox together`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        val general = GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }
        assertEquals(WorkerDisposition.ACKNOWLEDGE, general.runGeneral(jobId, 1))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select stage, browser_attempted from analysis_jobs").use { rows ->
                rows.next()
                assertEquals("BROWSER_PENDING", rows.getString(1))
                assertEquals(true, rows.getBoolean(2))
            }
            connection.createStatement().executeQuery("select count(*) from outbox_events where event_type='BROWSER_ANALYSIS'").use { rows ->
                rows.next()
                assertEquals(1, rows.getInt(1))
            }
        }
    }

    @Test
    fun `navigation timeout becomes partial without queue retry`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source, { throw BrowserNavigationTimeout() }, { _, _ -> error("classification must not run") })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("PARTIAL", rows.getString(1))
            }
        }
    }

    @Test
    fun `blocked browser destination becomes partial without retry`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source, { throw UnsafeUrlException("blocked") }, { _, _ -> error("classification must not run") })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
    }

    @Test
    fun `browser result writes metadata to the same item`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source,
            { Metadata("Rendered product", null, null, "https://example.com/item") },
            { _, _ -> ProcessingOutcome.Complete })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select product_name, analysis_status from wishlist_items").use { rows ->
                rows.next()
                assertEquals("Rendered product", rows.getString(1))
                assertEquals("READY", rows.getString(2))
            }
        }
    }

    @Test
    fun `browser metadata without usable classification stays partial`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source,
            { Metadata("Rendered product", null, null, "https://example.com/item") },
            { _, _ -> ProcessingOutcome.Partial })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select product_name,analysis_status from wishlist_items").use { rows ->
                rows.next(); assertEquals("Rendered product", rows.getString(1)); assertEquals("PARTIAL", rows.getString(2))
            }
        }
    }

    @Test
    fun `revoked browser claim cannot write metadata or ready status`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source,
            { id ->
                source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='CANCELLED' where id=?").use { s -> s.setObject(1,id); s.executeUpdate() } }
                Metadata("Stale product", null, null, "https://example.com/item")
            },
            { _, _ -> ProcessingOutcome.Complete })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select product_name,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals(null, r.getString(1)); assertEquals("PROCESSING", r.getString(2))
        } }
    }

    @Test
    fun `infrastructure failure leaves browser claim for reconciler retry`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source, { error("browser runtime exited") }, { _, _ -> error("classification must not run") })
        assertFailsWith<IllegalStateException> { browser.runBrowser(jobId, 1) }
        database.createConnection("").use { connection ->
            connection.prepareStatement("update analysis_jobs set updated_at=now()-interval '121 seconds' where id=?").use {
                it.setObject(1, jobId)
                it.executeUpdate()
            }
        }
        assertEquals(1, AnalysisJobReconciler(source).reconcileExpired())
        database.createConnection("").use { connection ->
            connection.createStatement().executeQuery("select stage from analysis_jobs").use { rows ->
                rows.next()
                assertEquals("BROWSER_PENDING", rows.getString(1))
            }
            connection.createStatement().executeQuery("select count(*) from outbox_events where event_type='BROWSER_ANALYSIS'").use { rows ->
                rows.next()
                assertEquals(2, rows.getInt(1))
            }
        }
    }

    private fun withJob(block: (PostgreSQLContainer<*>, UUID) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
                .create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }
            block(database, jobId)
        }
    }
}
