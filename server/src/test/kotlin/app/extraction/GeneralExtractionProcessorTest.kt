package app.extraction

import app.DatabaseFactory
import app.analysis.GeneralWorkerService
import app.analysis.WorkerDisposition
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.testcontainers.containers.PostgreSQLContainer

class GeneralExtractionProcessorTest {
    @Test
    fun `complete extraction stores metadata before marking item ready`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
            }
            val processor = GeneralExtractionProcessor(source) { url ->
                ExtractionResult.Complete(Metadata("A product", "Description", null, url))
            }

            assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
            database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select product_name, analysis_status from wishlist_items").use { rows ->
                    rows.next()
                    assertEquals("A product", rows.getString(1))
                    assertEquals("READY", rows.getString(2))
                }
            }
        }
    }

    @Test
    fun `insufficient metadata becomes partial and asks for manual completion`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
            }
            val processor = GeneralExtractionProcessor(source) { ExtractionResult.Partial }
            assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
            database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                    rows.next()
                    assertEquals("PARTIAL", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun `blocked redirect is terminal without retry`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
            }
            val processor = GeneralExtractionProcessor(source) { throw UnsafeUrlException("private redirect") }
            assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
            database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select analysis_status from wishlist_items").use { rows ->
                    rows.next()
                    assertEquals("FAILED_TERMINAL", rows.getString(1))
                }
            }
        }
    }
}
