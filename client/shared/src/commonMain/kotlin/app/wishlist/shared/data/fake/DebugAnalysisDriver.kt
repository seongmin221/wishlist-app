package app.wishlist.shared.data.fake

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.CategoryMissingReason
import app.wishlist.shared.model.WishlistItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DEBUG only: stands in for the server's analysis. Each call completes the current account's
 * PROCESSING items that are at least [minAge] old as READY, named after the link's host and filed
 * under the first seed leaf category. It has no timer; the coordinator runs it before each refresh.
 */
internal class DebugAnalysisDriver(
    private val store: FakeStore,
    private val clock: Clock,
    private val minAge: Duration = 5.seconds,
) {
    /** How many items were completed. */
    suspend fun advance(): ClientResult<Int> {
        val leaf = when (val categories = store.categories()) {
            is ClientResult.Failure -> return categories
            is ClientResult.Success -> categories.value.firstOrNull { it.parentId != null }?.id
        }
        return store.completeDueAnalyses(clock.now(), minAge) { item -> outcome(item, leaf) }
    }

    private fun outcome(item: WishlistItem, leaf: String?): AnalysisOutcome {
        val name = DisplayFormat.host(item.sourceUrl)
        return if (leaf != null) {
            AnalysisOutcome(AnalysisStatus.READY, name, leaf, missingReason = null, failureCode = null)
        } else {
            // Unseeded namespace: no category to file under.
            AnalysisOutcome(AnalysisStatus.PARTIAL, name, null, CategoryMissingReason.EXTRACTION_UNRESOLVED, null)
        }
    }
}
