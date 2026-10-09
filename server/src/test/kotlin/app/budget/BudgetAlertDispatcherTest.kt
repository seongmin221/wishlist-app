package app.budget

import app.testutil.claimJob
import app.analysis.AnalysisClaim
import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import app.testutil.PostgresTestContainer

class BudgetAlertDispatcherTest {
    @Test fun `failed notification stays pending and successful retry is delivered once`() {
        PostgresTestContainer().use { db ->
            db.start(); DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(),UUID.randomUUID(),"https://example.com/item")
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1,UUID::class.java) } }
            val claim = claimJob(source, jobId)
            val service = LlmBudgetService(source,dailyCeilingMicrousd=1200,monthlyCeilingMicrousd=1200)
            repeat(2) { service.reserveBeforeCall(claim,UUID.randomUUID()) }
            val dispatcher = BudgetAlertDispatcher(source)
            assertEquals(0,dispatcher.dispatch(2) { error("notifier unavailable") })
            assertEquals(2,service.pendingAlerts().size)
            assertEquals(2,dispatcher.dispatch(2) { })
            assertEquals(0,service.pendingAlerts().size)
            assertEquals(0,dispatcher.dispatch(2) { error("duplicate notification") })
        }
    }
}
