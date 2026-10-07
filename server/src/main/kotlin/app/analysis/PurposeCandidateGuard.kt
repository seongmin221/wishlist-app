package app.analysis

import java.sql.Connection
import java.util.UUID

internal sealed interface PurposeDecision {
    data object NoJudgment : PurposeDecision
    data class Judged(val purposeId: UUID?) : PurposeDecision
}

internal object PurposeCandidateGuard {
    /** Owner lock is held; every purpose writer takes it first, so no purpose row lock is needed here. */
    /** [currentPurpose] is the item's existing link; "no purpose" only judges a link the model saw unchanged. */
    fun decide(connection: Connection, claim: AnalysisClaim, stored: StoredCandidates, pendingPurpose: String?, judged: Boolean,
        currentPurpose: String?): PurposeDecision {
        val snapshot = stored.snapshot?.takeIf { it.schemaVersion == 3 && it.ownerId == claim.ownerId.toString() }
            ?: return PurposeDecision.NoJudgment
        if (!judged) return PurposeDecision.NoJudgment
        if (pendingPurpose == null) {
            if (currentPurpose == null) return PurposeDecision.Judged(null)
            val seen = snapshot.purposeCandidates.firstOrNull { it.id == currentPurpose } ?: return PurposeDecision.NoJudgment
            return if (unchanged(connection, claim, seen)) PurposeDecision.Judged(null) else PurposeDecision.NoJudgment
        }
        val candidate = snapshot.purposeCandidates.firstOrNull { it.id == pendingPurpose } ?: return PurposeDecision.NoJudgment
        return if (unchanged(connection, claim, candidate)) PurposeDecision.Judged(UUID.fromString(candidate.id)) else PurposeDecision.NoJudgment
    }

    /** The purpose is still ACTIVE for this owner with the name and description the model was given. */
    private fun unchanged(connection: Connection, claim: AnalysisClaim, candidate: app.ai.PurposeCandidate): Boolean {
        val current = connection.prepareStatement(
            "select name,description from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'",
        ).use { s ->
            s.setObject(1, claim.ownerId); s.setObject(2, UUID.fromString(candidate.id))
            s.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null }
        }
        return current == candidate.name to candidate.description
    }
}
