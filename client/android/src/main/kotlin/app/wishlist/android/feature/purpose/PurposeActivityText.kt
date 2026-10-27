package app.wishlist.android.feature.purpose

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.wishlist.android.R
import app.wishlist.android.feature.detail.deviceUtcOffsetSeconds
import app.wishlist.android.feature.detail.rememberMinuteClock
import app.wishlist.android.feature.home.HomeRowText
import app.wishlist.android.feature.home.resolve
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.PurposeActivity

/** A purpose's last activity: "어제 후보 추가" / "3일 전 만듦", or only the relative time for another kind (목적 탭·홈 요약 공용). */
@Composable
internal fun purposeActivityText(activity: PurposeActivity): String {
    val now = rememberMinuteClock()
    val time = HomeRowText.time(DisplayFormat.relative(activity.at, now, ::deviceUtcOffsetSeconds)).resolve()
    val format = when (activity.kind) {
        "CANDIDATE_ADDED" -> R.string.purpose_activity_added
        "CREATED" -> R.string.purpose_activity_created
        else -> null
    }
    return format?.let { stringResource(it, time) } ?: time
}
