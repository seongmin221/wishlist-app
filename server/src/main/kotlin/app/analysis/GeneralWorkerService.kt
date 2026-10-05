package app.analysis

import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.sql.DataSource

enum class WorkerDisposition { ACKNOWLEDGE, RETRY }
enum class ProcessingOutcome { Complete, NeedsBrowser, Partial, Retryable, Terminal, Stale }

class GeneralWorkerService(
    dataSource: DataSource,
    private val execution: WorkerExecution,
    private val process: (AnalysisClaim) -> ProcessingOutcome,
) {
    constructor(dataSource: DataSource, process: (AnalysisClaim) -> ProcessingOutcome) : this(dataSource, WorkerExecution.shared, process)
    private val claims = AnalysisClaimRepository(dataSource)
    private val results = AnalysisResultRepository(dataSource)

    fun runGeneral(jobId: UUID, generation: Int): WorkerDisposition = execution.run { runClaimed(jobId, generation) }

    private fun runClaimed(jobId: UUID, generation: Int): WorkerDisposition {
        val claim = when (val result = claims.claim(jobId, generation, AnalysisLane.GENERAL)) {
            is ClaimResult.Claimed -> result.claim
            ClaimResult.Ignored, ClaimResult.Exhausted -> return WorkerDisposition.ACKNOWLEDGE
        }
        val outcome = try { process(claim) } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            ProcessingOutcome.Retryable
        }
        return results.finish(claim, if (WorkerExecution.expired()) ProcessingOutcome.Retryable else outcome)
    }
}
