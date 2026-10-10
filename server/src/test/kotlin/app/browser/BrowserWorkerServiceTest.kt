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
import app.testutil.PostgresTestContainer
import org.testcontainers.containers.PostgreSQLContainer

import app.testutil.*
import app.analysis.AnalysisLane

class BrowserWorkerServiceTest {
    @Test fun `assembled browser path saves metadata exactly once before classification`() = withAnalysisDatabase { source ->
        val job = newFinishJob(source, AnalysisLane.BROWSER)
        val saves = java.util.concurrent.atomic.AtomicInteger()
        val observed = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                val c = source.connection
                return object : java.sql.Connection by c {
                    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
                        val statement = c.prepareStatement(sql)
                        if (!sql.startsWith("update analysis_jobs set pending_product_name=")) return statement
                        return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, method, args ->
                            val result = try { method.invoke(statement, *args.orEmpty()) }
                                catch (cause: java.lang.reflect.InvocationTargetException) { throw cause.targetException }
                            if (method.name == "executeUpdate") saves.incrementAndGet()
                            result
                        } as java.sql.PreparedStatement
                    }
                }
            }
        }
        val processor = BrowserRenderProcessor(observed) { url -> Metadata("Rendered once", null, null, url) }
        val worker = BrowserWorkerService(observed, processor::render, { claim, _ ->
            assertEquals("Rendered once", analysisScalar(source, "select pending_product_name from analysis_jobs where id='${claim.jobId}'"))
            app.analysis.ProcessingOutcome.Partial
        })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, worker.runBrowser(job.jobId, 1))
        assertEquals(1, saves.get())
        assertEquals("Rendered once", analysisScalar(source, "select product_name from wishlist_items where id='${job.itemId}'"))
    }
    @Test fun `browser stale claims preserve fields and cancel version-invalid current executions`() = withAnalysisDatabase { source ->
        assertStaleFinishMatrix(source, AnalysisLane.BROWSER)
    }

    @Test fun `browser final outcomes update version once and invalidate execution`() = withAnalysisDatabase { source ->
        assertNormalFinishMatrix(source, AnalysisLane.BROWSER)
    }

    @Test fun `browser results preserve user and override values and finalized review`() = withAnalysisDatabase { source ->
        assertProtectedFinishMatrix(source, AnalysisLane.BROWSER)
    }

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
    fun `browser dns failure becomes partial without retry`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source, { throw app.extraction.DnsLookupFailed() }, { _, _ -> error("classification must not run") })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        assertEquals("PARTIAL", analysisScalar(source, "select analysis_status from wishlist_items"))
        assertEquals("1", analysisScalar(source, "select count(*) from outbox_events where event_type='BROWSER_ANALYSIS'"))
    }

    @Test
    fun `browser result writes metadata to the same item`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source,
            { Metadata("Rendered product", null, null, "https://example.com/item") },
            { id, _ ->
                source.connection.use { c -> c.prepareStatement("update analysis_jobs set pending_category_id='C026' where id=?").use { s -> s.setObject(1,id.jobId); s.executeUpdate() } }
                ProcessingOutcome.Complete
            })
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
                source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='CANCELLED' where id=?").use { s -> s.setObject(1,id.jobId); s.executeUpdate() } }
                Metadata("Stale product", null, null, "https://example.com/item")
            },
            { _, _ -> ProcessingOutcome.Complete })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select product_name,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals(null, r.getString(1)); assertEquals("PROCESSING", r.getString(2))
        } }
    }

    @Test
    fun `infrastructure failure retries current browser execution without fallback duplication`() = withJob { database, jobId ->
        val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
        GeneralWorkerService(source) { ProcessingOutcome.NeedsBrowser }.runGeneral(jobId, 1)
        val browser = BrowserWorkerService(source, { error("browser runtime exited") }, { _, _ -> error("classification must not run") })
        assertEquals(WorkerDisposition.ACKNOWLEDGE, browser.runBrowser(jobId, 1))
        assertEquals("BROWSER_PENDING", analysisScalar(source, "select stage from analysis_jobs where id='$jobId'"))
        kotlin.test.assertNull(analysisScalar(source, "select execution_token from analysis_jobs where id='$jobId'"))
        assertEquals("2", analysisScalar(source, "select count(*) from outbox_events where event_type='BROWSER_ANALYSIS'"))
    }

    private fun withJob(block: (PostgreSQLContainer<*>, UUID) -> Unit) {
        PostgresTestContainer().use { database ->
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
