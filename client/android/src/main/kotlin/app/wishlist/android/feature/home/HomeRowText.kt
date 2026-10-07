package app.wishlist.android.feature.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.wishlist.android.R
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.presentation.HomeRow
import app.wishlist.shared.presentation.RowStatus

/** A string resource with its format argument (if any); resolved only in composition. */
data class ResText(@param:StringRes val res: Int, val arg: Any? = null)

/** Maps the Presenter's row values to the 문구 표 keys. Wording is the platform's; the Presenter only classifies. */
object HomeRowText {
    fun time(time: RelativeTime): ResText = when (time) {
        RelativeTime.JustNow -> ResText(R.string.time_just_now)
        is RelativeTime.Minutes -> ResText(R.string.time_minutes, time.value)
        is RelativeTime.Hours -> ResText(R.string.time_hours, time.value)
        RelativeTime.Yesterday -> ResText(R.string.time_yesterday)
        is RelativeTime.Days -> ResText(R.string.time_days, time.value)
    }

    /** The row's second line. Only the signed-out row (`%1$s 저장 · 이 기기에만 있어요`) takes the saved time. */
    @StringRes
    fun meta(status: RowStatus): Int = when (status) {
        RowStatus.LOCAL_ONLY -> R.string.home_pending_meta
        RowStatus.SENDING -> R.string.row_sending
        RowStatus.WAITING_NETWORK -> R.string.row_waiting_network
        RowStatus.FAILED -> R.string.row_failed
        RowStatus.PROCESSING -> R.string.row_processing
    }

    fun metaShowsTime(status: RowStatus): Boolean = status == RowStatus.LOCAL_ONLY

    /** Logged-in header caption "할 일 N개" (N = the 분류 중 rows, the only to-do card in C3). */
    fun todoCount(count: Int): ResText = ResText(R.string.home_todo_count, count)
}

@Composable
internal fun ResText.resolve(): String = if (arg == null) stringResource(res) else stringResource(res, arg)

@Composable
internal fun HomeRow.metaText(): String {
    val res = HomeRowText.meta(status)
    return if (HomeRowText.metaShowsTime(status)) stringResource(res, HomeRowText.time(savedAt).resolve()) else stringResource(res)
}
