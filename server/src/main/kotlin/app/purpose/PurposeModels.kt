package app.purpose

import app.common.FieldChange
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
    /** Must be at least ACTIVE_LIMIT: one default page returns every active purpose, so activity reordering cannot skip any. */
    const val PAGE_LIMIT = ACTIVE_LIMIT
}

class PurposeException(val code: String, val fields: Set<String> = emptySet(), val currentVersion: Int? = null,
    val retryAfterSeconds: Int? = null) : RuntimeException(code)

enum class PurposeProjection { SUMMARY, SELECT }
data class PurposeCursorPosition(val activityAt: Instant, val id: UUID)
data class PurposePreview(val itemId: UUID, val imageUrl: String?)
data class PurposeListEntry(val purpose: Purpose, val previews: List<PurposePreview>)
data class PurposePage(val projection: PurposeProjection, val entries: List<PurposeListEntry>, val next: PurposeCursorPosition?,
    val activeCount: Int, val archivedCount: Int)

data class PurposeChanges(
    val name: String? = null,
    val description: FieldChange<String?> = FieldChange.Keep,
    val color: PurposeColor? = null,
    val icon: PurposeIcon? = null,
) {
    val hasChanges: Boolean get() = name != null || description != FieldChange.Keep || color != null || icon != null
    fun applyTo(current: PurposeInput) = PurposeInput(
        name ?: current.name,
        when (val value = description) { FieldChange.Keep -> current.description; is FieldChange.Set -> value.value },
        color ?: current.color, icon ?: current.icon,
    )
}
