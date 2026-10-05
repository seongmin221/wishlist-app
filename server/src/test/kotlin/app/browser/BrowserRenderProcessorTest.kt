package app.browser

import app.DatabaseFactory
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import app.testutil.PostgresTestContainer

import app.testutil.*
import app.analysis.*
import kotlin.test.assertNull

class BrowserRenderProcessorTest {
    @Test fun `render finishing after token rotation cannot save pending metadata`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source, AnalysisLane.BROWSER)
        var expected: String? = null
        val rendered = pausedAnalysisCall({ pause ->
            BrowserRenderProcessor(source) { pause(); Metadata("stale render", null, null, "https://example.com/item") }.render(claim)
        }, {
            analysisSql(source, "update analysis_jobs set execution_token='${UUID.randomUUID()}' where id='${claim.jobId}'")
            expected = pendingSnapshot(source, claim)
        })
        assertNull(rendered)
        assertEquals(expected, pendingSnapshot(source, claim))
        var calls = 0
        assertNull(BrowserRenderProcessor(source) { calls++; null }.render(claim))
        assertEquals(0, calls)
    }

    @Test
    fun `processor renders the source url belonging to its job`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = database.createConnection("").use { connection ->
                connection.createStatement().executeQuery("select id from analysis_jobs").use { rows -> rows.next(); rows.getObject(1, UUID::class.java) }
            }
            database.createConnection("").use { connection ->
                connection.prepareStatement("update analysis_jobs set stage='BROWSER_PENDING', browser_attempted=true where id=?").use {
                    it.setObject(1, jobId)
                    it.executeUpdate()
                }
            }
            val claim = claimJob(source, jobId, AnalysisLane.BROWSER)
            var renderedUrl: String? = null
            val processor = BrowserRenderProcessor(source) { url ->
                renderedUrl = url
                Metadata("Rendered", null, null, url)
            }

            assertEquals("Rendered", processor.render(claim)?.title)
            assertEquals("https://example.com/item", renderedUrl)
        }
    }
}
