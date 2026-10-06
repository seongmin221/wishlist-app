package app.analysis

import java.time.Instant
import java.util.UUID

enum class AnalysisLane { GENERAL, BROWSER }

data class AnalysisClaim(
    val jobId: UUID,
    val itemId: UUID,
    val ownerId: UUID,
    val generation: Int,
    val executionToken: UUID,
    val lane: AnalysisLane,
    val expectedItemVersion: Int,
    val leaseUntil: Instant,
)

sealed interface ClaimResult {
    data class Claimed(val claim: AnalysisClaim) : ClaimResult
    data object Ignored : ClaimResult
    data object Exhausted : ClaimResult
}
