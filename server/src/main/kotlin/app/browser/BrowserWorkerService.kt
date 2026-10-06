package app.browser

import app.analysis.*
import app.extraction.Metadata
import app.extraction.UnsafeUrlException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException

class BrowserNavigationTimeout : RuntimeException()
class BrowserSiteBlocked : RuntimeException()
class BrowserTargetUnavailable : RuntimeException()

class BrowserWorkerService(
    dataSource: DataSource,
    private val render: (AnalysisClaim) -> Metadata?,
    private val classify: (AnalysisClaim, Metadata) -> ProcessingOutcome,
    private val execution: WorkerExecution = WorkerExecution.shared,
) {
    private val claims = AnalysisClaimRepository(dataSource)
    private val pending = AnalysisPendingResultRepository(dataSource)
    private val results = AnalysisResultRepository(dataSource)

    fun runBrowser(jobId: UUID, generation: Int): WorkerDisposition = execution.run { runClaimed(jobId, generation) }

    private fun runClaimed(jobId: UUID, generation: Int): WorkerDisposition {
        if (WorkerExecution.expired()) return WorkerDisposition.RETRY
        val claimResult = try { claims.claim(jobId, generation, AnalysisLane.BROWSER) }
            catch (_: ProcessingDeadlineExceeded) { return WorkerDisposition.RETRY }
        val claim = when (val result = claimResult) {
            is ClaimResult.Claimed -> result.claim
            ClaimResult.Ignored, ClaimResult.Exhausted -> return WorkerDisposition.ACKNOWLEDGE
        }
        val outcome = try {
            val metadata = try { render(claim) } catch (_: BrowserNavigationTimeout) { null }
                catch (_: BrowserSiteBlocked) { null }
                catch (_: BrowserTargetUnavailable) { null }
                catch (_: UnsafeUrlException) { null }
            when {
                metadata == null -> ProcessingOutcome.Partial
                !pending.saveMetadata(claim, metadata) -> ProcessingOutcome.Stale
                else -> classify(claim, metadata)
            }
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            ProcessingOutcome.Retryable
        }
        return results.finish(claim, if (WorkerExecution.expired()) ProcessingOutcome.Retryable else outcome)
    }
}
