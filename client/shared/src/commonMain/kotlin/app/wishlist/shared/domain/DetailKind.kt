package app.wishlist.shared.domain

import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.WishlistItem

enum class DetailKind { PROCESSING, READY, INCOMPLETE, GONE }

/** Why an INCOMPLETE item needs work; the platform maps it to its sentence. */
enum class DetailNotice { INFORMATION_MISSING, CATEGORY_UNDECIDED, CATEGORY_DELETED }

data class DetailPresentation(val kind: DetailKind, val notice: DetailNotice?)

object DetailKinds {
    private val ready = DetailPresentation(DetailKind.READY, null)
    private val processing = DetailPresentation(DetailKind.PROCESSING, null)
    private val missing = DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.INFORMATION_MISSING)

    /** lifecycle first (non-ACTIVE always has requiredAction NONE), then requiredAction; UNKNOWN falls back to the fields. */
    fun of(item: WishlistItem): DetailPresentation {
        when (item.lifecycleStatus) {
            LifecycleStatus.DELETED -> return DetailPresentation(DetailKind.GONE, null)
            LifecycleStatus.ARCHIVED -> return ready
            LifecycleStatus.ACTIVE -> Unit
        }
        return when (item.requiredAction) {
            RequiredAction.ANALYSIS_IN_PROGRESS -> processing
            RequiredAction.INFORMATION_COMPLETION -> missing
            RequiredAction.CATEGORY_ASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_UNDECIDED)
            RequiredAction.CATEGORY_REASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_DELETED)
            RequiredAction.CLASSIFICATION_REVIEW, RequiredAction.NONE -> ready
            RequiredAction.UNKNOWN -> when {
                item.analysis.status == AnalysisStatus.PROCESSING -> processing
                item.product.name == null || item.category.id == null -> missing
                else -> ready
            }
        }
    }
}
