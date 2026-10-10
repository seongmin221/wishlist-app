package app.maintenance

import app.analysis.PendingRecoveryReport
import app.budget.BudgetMaintenanceReport
import app.tasks.DispatchReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceServiceTest {
    private val pending = PendingRecoveryReport(1, 2, 3, 4, 5, 6)

    @Test fun `steps run in order and their counts are reported`() {
        val order = mutableListOf<String>()
        val report = MaintenanceService(
            dispatch = { _, _ -> order += "dispatch"; DispatchReport(7, 1) },
            reconcile = { order += "reconcile"; 2 },
            recoverPending = { order += "pending"; pending },
            budget = { order += "budget"; BudgetMaintenanceReport(3, 4) },
        ).runOnce()
        assertEquals(listOf("dispatch", "reconcile", "pending", "budget"), order)
        assertEquals(MaintenanceReport(7, 1, 2, 1, 2, 3, 4, 5, 6, BudgetMaintenanceReport(3, 4), emptyList()), report)
    }

    @Test fun `a failing step is isolated logged without its message and later steps still run`() {
        val logs = mutableListOf<Pair<String, String>>()
        val report = MaintenanceService(
            dispatch = { _, _ -> DispatchReport(0, 0) },
            reconcile = { throw IllegalStateException("secret sql text") },
            recoverPending = { pending },
            budget = { throw IllegalStateException("secret") },
            log = { step, type -> logs += step to type },
        ).runOnce()
        assertEquals(listOf("reconcile", "budget"), report.failedSteps)
        assertEquals(1, report.pendingRescheduled)
        assertNull(report.budget)
        assertEquals(listOf("reconcile" to "java.lang.IllegalStateException", "budget" to "java.lang.IllegalStateException"), logs)
    }

    @Test fun `each step receives the shared run deadline and a spent budget skips the remaining steps`() {
        var now = 0L
        val deadlines = mutableListOf<Long>()
        val report = MaintenanceService(
            dispatch = { _, deadline -> deadlines += deadline; now += 30_000_000_000; DispatchReport(1, 0) },
            reconcile = { deadline -> deadlines += deadline; now += 30_000_000_000; 1 },
            recoverPending = { error("must not run after the deadline") },
            budget = { error("must not run after the deadline") },
            totalMillis = 50_000, nanoTime = { now },
        ).runOnce()
        assertEquals(listOf(50_000_000_000L, 50_000_000_000L), deadlines)
        assertEquals(listOf("timeout:pending", "timeout:budget"), report.failedSteps)
        assertTrue(report.publishedEvents == 1 && report.recoveredRunning == 1)
    }
}
