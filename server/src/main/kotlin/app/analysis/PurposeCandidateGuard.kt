package app.analysis

import java.sql.Connection
import java.util.UUID

internal sealed interface PurposeDecision {
    data object NoJudgment : PurposeDecision
    data class Judged(val purposeId: UUID?) : PurposeDecision
}

internal object PurposeCandidateGuard {
    /** Owner lock is held; every purpose writer takes it first, so no purpose row lock is needed here. */
    fun decide(connection: Connection, claim: AnalysisClaim, stored: StoredCandidates, pendingPurpose: String?, judged: Boolean): PurposeDecision {
        val snapshot = stored.snapshot?.takeIf { it.schemaVersion == 3 && it.ownerId == claim.ownerId.toString() }
            ?: return PurposeDecision.NoJudgment
        if (!judged) return PurposeDecision.NoJudgment
        if (pendingPurpose == null) return PurposeDecision.Judged(null)
        val candidate = snapshot.purposeCandidates.firstOrNull { it.id == pendingPurpose } ?: return PurposeDecision.NoJudgment
        val id = UUID.fromString(candidate.id)
        val current = connection.prepareStatement(
            "select name,description from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'",
        ).use { s ->
            s.setObject(1, claim.ownerId); s.setObject(2, id)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null }
        }
        return if (current == candidate.name to candidate.description) PurposeDecision.Judged(id) else PurposeDecision.NoJudgment
    }
}
