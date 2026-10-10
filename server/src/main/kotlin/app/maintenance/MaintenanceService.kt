package app.maintenance

import app.analysis.PendingRecoveryReport
import app.budget.BudgetMaintenanceReport
import app.tasks.DispatchReport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class MaintenanceReport(
    val publishedEvents: Int, val failedEvents: Int, val recoveredRunning: Int,
    val pendingRescheduled: Int, val pendingFailed: Int, val pendingRescheduleExhausted: Int,
    val pendingCancelled: Int, val pendingAlive: Int, val lookupFailed: Int,
    val budget: BudgetMaintenanceReport?, val failedSteps: List<String>,
)

/**
 * One Scheduler tick: publish the outbox backlog, recover expired RUNNING, recover stale PENDING, settle the LLM
 * budget. Steps are isolated: one failure is logged and reported while later steps still run. All steps share one
 * run deadline; a step reached after it is skipped and reported as a timeout.
 */
class MaintenanceService(
    private val dispatch: (Int, Long) -> DispatchReport,
    private val reconcile: (Long) -> Int,
    private val recoverPending: (Long) -> PendingRecoveryReport,
    private val budget: () -> BudgetMaintenanceReport,
    private val totalMillis: Long = 50_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String, String) -> Unit = { step, type ->
        LoggerFactory.getLogger(MaintenanceService::class.java).error("Maintenance step failed step={} exceptionType={}", step, type)
    },
) {
    fun runOnce(): MaintenanceReport {
        val deadline = nanoTime() + totalMillis * 1_000_000
        val failed = mutableListOf<String>()
        fun <T> step(name: String, action: () -> T): T? {
            if (nanoTime() - deadline >= 0) { failed += "timeout:$name"; return null }
            return try { action() } catch (cause: Exception) {
                if (cause is CancellationException || cause is InterruptedException) throw cause
                log(name, cause.javaClass.name); failed += name; null
            }
        }
        val published = step("dispatch") { dispatch(BACKLOG_LIMIT, deadline) }
        val recovered = step("reconcile") { reconcile(deadline) }
        val pending = step("pending") { recoverPending(deadline) }
        val settled = step("budget") { budget() }
        return MaintenanceReport(
            publishedEvents = published?.published ?: 0, failedEvents = published?.failed ?: 0, recoveredRunning = recovered ?: 0,
            pendingRescheduled = pending?.rescheduled ?: 0, pendingFailed = pending?.failed ?: 0,
            pendingRescheduleExhausted = pending?.rescheduleExhausted ?: 0, pendingCancelled = pending?.cancelled ?: 0,
            pendingAlive = pending?.alive ?: 0, lookupFailed = pending?.lookupFailed ?: 0, budget = settled, failedSteps = failed,
        )
    }

    private companion object { const val BACKLOG_LIMIT = 100 }
}
