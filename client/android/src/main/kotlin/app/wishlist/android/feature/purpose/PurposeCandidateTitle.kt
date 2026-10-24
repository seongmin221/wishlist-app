package app.wishlist.android.feature.purpose

import androidx.annotation.StringRes
import app.wishlist.android.R
import app.wishlist.shared.model.AnalysisStatus

/** B4 permits unnamed candidates; preserve named values and display the analysis state otherwise. */
@StringRes
internal fun purposeCandidateTitleFallback(name: String?, analysis: AnalysisStatus): Int? = when {
    !name.isNullOrBlank() -> null
    analysis == AnalysisStatus.PROCESSING -> R.string.home_processing_title
    else -> R.string.purpose_candidate_needs_info
}
