package app.candidates

import app.category.CategoryRef
import app.wishlist.ReadPosition
import app.wishlist.WishlistItem
import java.util.UUID

object CandidateLimits {
    const val DEFAULT_PAGE = 20
    const val MAX_PAGE = 100
    const val MAX_MOVE = 100
}

/** PUR-07 filter. None is the whole pool; no wire string stands for "unfiltered". */
sealed interface CandidateFilter {
    data object None : CandidateFilter
    data class Category(val ref: CategoryRef) : CandidateFilter
    data class Parent(val id: String) : CandidateFilter
}

data class CategoryFacet(val categoryId: String, val parentId: String, val count: Long)
data class CandidateFacets(val categories: List<CategoryFacet>, val unassignedCount: Long)
data class CandidatePage(val items: List<WishlistItem>, val totalCount: Long, val next: ReadPosition?, val facets: CandidateFacets?)

data class MoveSelection(val id: UUID, val expectedVersion: Int, val expectedPurposeId: UUID?)
data class CandidateMoveRequest(val expectedVersion: Int, val items: List<MoveSelection>)

data class MovedItem(val id: UUID, val version: Int, val purposeId: UUID)
data class MovedPurpose(val id: UUID, val candidateCount: Long, val membershipVersion: Int)

/** [replayed] is not stored: a replay returns the stored body with the replay header. */
data class CandidateMoveResult(val items: List<MovedItem>, val purposes: List<MovedPurpose>, val replayed: Boolean)

enum class DeletionKind(val notFound: String, val conflict: String) {
    CATEGORY("CATEGORY_NOT_FOUND", "CATEGORY_DELETION_IMPACT_CONFLICT"),
    PURPOSE("PURPOSE_NOT_FOUND", "PURPOSE_DELETION_IMPACT_CONFLICT"),
}

/** CAT-05/PUR-05 page. [impactToken] fingerprints the whole ACTIVE impact, never just this page. */
data class StructureImpact(val version: Int, val impactToken: String, val affectedCount: Long, val items: List<WishlistItem>, val next: ReadPosition?)

/**
 * B8 service errors. Item conflicts name the owner's item and its current version; a target version conflict carries
 * only [currentVersion]; not-found errors carry nothing so other owners' rows stay invisible.
 */
class CandidateException(
    val code: String,
    val itemId: UUID? = null,
    val currentVersion: Int? = null,
    val fields: Set<String> = emptySet(),
) : RuntimeException(code)

/** PostgreSQL compares UUIDs by bytes, which matches the order of their canonical lowercase text. */
internal val SQL_UUID_ORDER: Comparator<UUID> = compareBy { it.toString() }
