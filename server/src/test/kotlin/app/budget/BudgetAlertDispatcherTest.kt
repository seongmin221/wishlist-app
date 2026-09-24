package app.budget

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.testcontainers.containers.PostgreSQLContainer

class BudgetAlertDispatcherTest {
    @Test fun `failed notification stays pending and successful retry is delivered once`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start(); DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(),UUID.randomUUID(),"https://example.com/item")
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1,UUID::class.java) } }
            source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING' where id=?").use { s -> s.setObject(1,jobId); s.executeUpdate() } }
            val service = LlmBudgetService(source,dailyCeilingMicrousd=1000,monthlyCeilingMicrousd=1000)
            repeat(2) { service.reserveBeforeCall(jobId,1,UUID.randomUUID()) }
            val dispatcher = BudgetAlertDispatcher(source)
            assertEquals(0,dispatcher.dispatch(2) { error("notifier unavailable") })
            assertEquals(2,service.pendingAlerts().size)
            assertEquals(2,dispatcher.dispatch(2) { })
            assertEquals(0,service.pendingAlerts().size)
            assertEquals(0,dispatcher.dispatch(2) { error("duplicate notification") })
        }
    }
}
