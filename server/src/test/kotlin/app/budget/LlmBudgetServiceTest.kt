package app.budget

import app.testutil.claimJob
import app.analysis.AnalysisClaim
import app.DatabaseFactory
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
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
    @Test fun `reservation flight and default recovery use database clock despite application clock difference`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val shifted = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                val connection = source.connection
                return java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                    val result = try { method.invoke(connection, *args.orEmpty()) } catch (cause: java.lang.reflect.InvocationTargetException) { throw cause.targetException }
                    if (method.name != "createStatement") result else {
                        val statement = result as java.sql.Statement
                        java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement::class.java.classLoader, arrayOf(java.sql.Statement::class.java)) { _, sm, sa ->
                            val actual = if (sm.name == "executeQuery" && sa?.firstOrNull() == "select clock_timestamp()") arrayOf<Any>("select clock_timestamp()+interval '30 seconds'") else sa.orEmpty()
                            try { sm.invoke(statement, *actual) } catch (cause: java.lang.reflect.InvocationTargetException) { throw cause.targetException }
                        }
                    }
                } as java.sql.Connection
            }
        }
        val budget = LlmBudgetService(shifted)
        val reservation = assertIs<ReserveResult.Reserved>(budget.reserveBeforeCall(claim, UUID.randomUUID())).reservation
        fun remaining() = analysisScalar(source, "select extract(epoch from lease_until-clock_timestamp()) from llm_budget_reservations where id='${reservation.id}'")!!.toDouble()
        kotlin.test.assertTrue(remaining() in 145.0..155.0)
        budget.markInFlight(reservation.id)
        kotlin.test.assertTrue(remaining() in 145.0..155.0)
        analysisSql(source, "update llm_budget_reservations set lease_until=clock_timestamp()+interval '20 seconds' where id='${reservation.id}'")
        assertEquals(1, budget.reconcileExpired())
        assertEquals(ReservationState.SETTLED, budget.state(reservation.id))
    }

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
        assertEquals(596L, budget.windowTotals("DAILY").reserved)
    }

    @Test fun `concurrent reservations cannot exceed either ceiling`() = withBudget { service, claim ->
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..8).map { Callable { service.reserveBeforeCall(claim, UUID.randomUUID()) } })
                .map { it.get() }
            assertEquals(2, results.count { it is ReserveResult.Reserved })
            assertEquals(6, results.count { it is ReserveResult.Exceeded })
        assertEquals(1192L, service.windowTotals("DAILY").reserved)
        assertEquals(1192L, service.windowTotals("MONTHLY").reserved)
        } finally { executor.shutdownNow() }
    }

    @Test fun `expired reserved releases while in flight settles maximum`() = withAnalysisDatabase { source ->
        val service = LlmBudgetService(source)
        val claim = newAnalysisClaim(source)
        val before = assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        val sent = assertIs<ReserveResult.Reserved>(service.reserveBeforeCall(claim, UUID.randomUUID()))
        service.markInFlight(sent.reservation.id)
        analysisSql(source, "update llm_budget_reservations set lease_until=clock_timestamp()-interval '1 second'")
        assertEquals(2, service.reconcileExpired())
        assertEquals(596L, service.windowTotals("DAILY").settled)
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

    @Test fun `a window created with an older release ceiling adopts the configured ceiling`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        analysisSql(source, """insert into llm_budget_windows(id,window_type,window_start,reserved_microusd,settled_microusd,ceiling_microusd) values
            ('${UUID.randomUUID()}','DAILY',date_trunc('day',clock_timestamp() at time zone 'UTC') at time zone 'UTC',0,599500,600000),
            ('${UUID.randomUUID()}','MONTHLY',date_trunc('month',clock_timestamp() at time zone 'UTC') at time zone 'UTC',0,0,6000000)""")
        assertIs<ReserveResult.Reserved>(LlmBudgetService(source).reserveBeforeCall(claim, UUID.randomUUID()))
        assertEquals("721000", analysisScalar(source, "select ceiling_microusd from llm_budget_windows where window_type='DAILY'"))
        assertEquals("7210000", analysisScalar(source, "select ceiling_microusd from llm_budget_windows where window_type='MONTHLY'"))
    }

    private fun withBudget(block: (LlmBudgetService, AnalysisClaim) -> Unit) {
        PostgresTestContainer().use { db ->
            db.start()
            DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item") as CreateResult.Created
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { it.next(); it.getObject(1, UUID::class.java) } }
            val claim = claimJob(source, jobId)
            block(LlmBudgetService(source, dailyCeilingMicrousd = 1200, monthlyCeilingMicrousd = 1200), claim)
        }
    }
}
