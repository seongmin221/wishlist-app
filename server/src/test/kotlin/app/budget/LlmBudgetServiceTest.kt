package app.budget

import app.testutil.claimJob
import app.analysis.AnalysisClaim
import app.DatabaseFactory
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import app.testutil.PostgresTestContainer

import app.testutil.*
import app.analysis.*
import kotlin.test.assertNull

class LlmBudgetServiceTest {
    @Test fun `expired claim cannot reserve even when request id already exists`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val request = UUID.randomUUID()
        val reserved = kotlin.test.assertIs<ReserveResult.Reserved>(budget.reserveBeforeCall(claim, request)).reservation
        analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='${claim.jobId}'")
        assertEquals(ReserveResult.Stale, budget.reserveBeforeCall(claim, request))
        assertEquals(ReserveResult.Stale, budget.reserveBeforeCall(claim, UUID.randomUUID()))
        assertEquals("1", analysisScalar(source, "select count(*) from llm_budget_reservations"))
        assertEquals(ReservationState.RESERVED, budget.state(reserved.id))
        assertEquals(496L, budget.windowTotals("DAILY").reserved)
    }

    @Test fun `concurrent reservations cannot exceed either ceiling`() = withBudget { service, claim ->
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..8).map { Callable { service.reserveBeforeCall(claim, UUID.randomUUID()) } })
                .map { it.get() }
            assertEquals(2, results.count { it is ReserveResult.Reserved })
            assertEquals(6, results.count { it is ReserveResult.Exceeded })
        assertEquals(992L, service.windowTotals("DAILY").reserved)
        assertEquals(992L, service.windowTotals("MONTHLY").reserved)
        } finally { executor.shutdownNow() }
    }

    @Test fun `expired reserved releases while in flight settles maximum`() = withBudget { service, claim ->
        val before = assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        val sent = assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        service.markInFlight(sent.reservation.id)
        service.reconcileExpired(Instant.now().plusSeconds(121))
        assertEquals(496L, service.windowTotals("DAILY").settled)
        assertEquals(0L, service.windowTotals("DAILY").reserved)
        assertEquals(ReservationState.RELEASED, service.state(before.reservation.id))
        assertEquals(ReservationState.SETTLED, service.state(sent.reservation.id))
    }

    @Test fun `response settlement releases unused reservation`() = withBudget { service, claim ->
        val reservation = assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID())).reservation
        service.markInFlight(reservation.id)
        service.settle(reservation.id, 500, 20)
        assertEquals(124L, service.windowTotals("DAILY").settled)
        assertEquals(0L, service.windowTotals("DAILY").reserved)
    }

    @Test fun `unapproved price version is rejected`() = withBudget { _, _ ->
        assertFailsWith<IllegalArgumentException> { PriceTable("new-price", 0.20, 1.20) }
    }

    @Test fun `crossing eighty percent creates one durable alert per window`() = withBudget { service, claim ->
        assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        assertEquals(0, service.pendingAlerts().size)
        assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        assertEquals(setOf("DAILY", "MONTHLY"), service.pendingAlerts().map { it.windowType }.toSet())
        assertEquals(2, service.pendingAlerts().size)
    }

    private fun withBudget(block: (LlmBudgetService, AnalysisClaim) -> Unit) {
        PostgresTestContainer().use { db ->
            db.start()
            DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item") as CreateResult.Created
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { it.next(); it.getObject(1, UUID::class.java) } }
            val claim = claimJob(source, jobId)
            block(LlmBudgetService(source, dailyCeilingMicrousd = 1000, monthlyCeilingMicrousd = 1000), claim)
        }
    }
}
