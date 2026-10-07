package app.purpose

import java.time.Instant
import java.util.UUID

enum class PurposeActivityKind { CREATED, CANDIDATE_ADDED }
enum class PurposeAction { EDIT, DELETE, ADD_CANDIDATES, ARCHIVE }

data class Purpose(
    val id: UUID, val input: PurposeInput, val version: Int, val membershipVersion: Int, val candidateCount: Long,
    val activityAt: Instant, val activityKind: PurposeActivityKind, val createdAt: Instant, val updatedAt: Instant,
) {
    /** State-allowed actions. DELETE/ADD_CANDIDATES/ARCHIVE are implemented in B8/B10. */
    val allowedActions: List<PurposeAction> get() = PurposeAction.entries.filter { it != PurposeAction.ARCHIVE || candidateCount > 0 }
}

data class PurposeCreation(val purpose: Purpose, val activeCount: Int, val replayed: Boolean)

object PurposeLimits {
    const val ACTIVE_LIMIT = 30
    const val CREATES_PER_WINDOW = 10
    const val CREATE_WINDOW_SECONDS = 60
    const val PAGE_LIMIT = 30
}

class PurposeException(val code: String, val fields: Set<String> = emptySet(), val currentVersion: Int? = null,
    val retryAfterSeconds: Int? = null) : RuntimeException(code)
