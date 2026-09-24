package app.budget

import app.DatabaseFactory
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.testcontainers.containers.PostgreSQLContainer

class BudgetMaintenanceServiceTest {
    @Test fun `one maintenance run settles expired in flight and delivers pending alert`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start(); DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(),UUID.randomUUID(),"https://example.com/item")
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1,UUID::class.java) } }
            source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING' where id=?").use { s -> s.setObject(1,jobId); s.executeUpdate() } }
            val budget = LlmBudgetService(source,dailyCeilingMicrousd=1000,monthlyCeilingMicrousd=1000)
            val first = budget.reserveBeforeCall(jobId,1,UUID.randomUUID()) as ReserveResult.Reserved
            budget.markInFlight(first.reservation.id)
            budget.reserveBeforeCall(jobId,1,UUID.randomUUID())
            source.connection.use { c -> c.createStatement().executeUpdate("update llm_budget_reservations set lease_until=now()-interval '1 second'") }
            val notified = mutableSetOf<UUID>()
            val maintenance = BudgetMaintenanceService(source) { alert -> notified.add(alert.id) }

            assertEquals(MaintenanceReport(2,2),maintenance.runOnce())
            assertEquals(496L,budget.windowTotals("DAILY").settled)
            assertEquals(0L,budget.windowTotals("DAILY").reserved)
            assertEquals(2,notified.size)
            assertEquals(MaintenanceReport(0,0),maintenance.runOnce())
        }
    }

    @Test fun `missing database settings prevent maintenance startup`() {
        assertFailsWith<IllegalArgumentException> { BudgetMaintenanceConfig.fromEnvironment(emptyMap()) }
    }
}
