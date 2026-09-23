package app.browser

import app.DatabaseFactory
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.testcontainers.containers.PostgreSQLContainer

class BrowserRenderProcessorTest {
    @Test
    fun `processor renders the source url belonging to its job`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
            }
            database.createConnection("").use { connection ->
                connection.prepareStatement("update analysis_jobs set stage='BROWSER_RUNNING', browser_attempted=true where id=?").use {
                    it.setObject(1, jobId)
                    it.executeUpdate()
                }
            }
            var renderedUrl: String? = null
            val processor = BrowserRenderProcessor(source) { url ->
                renderedUrl = url
                Metadata("Rendered", null, null, url)
            }

            assertEquals("Rendered", processor.render(jobId)?.title)
            assertEquals("https://example.com/item", renderedUrl)
        }
    }
}
