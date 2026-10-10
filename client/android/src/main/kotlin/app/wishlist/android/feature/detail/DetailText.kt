package app.wishlist.android.feature.detail

import androidx.annotation.StringRes
import app.wishlist.android.R
import app.wishlist.android.designsystem.WLPurposeColor
import app.wishlist.android.feature.home.HomeRowText
import app.wishlist.android.feature.home.ResText
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.domain.DetailNotice
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.domain.SavedLabel
import app.wishlist.shared.model.PurposeKeys
import app.wishlist.shared.presentation.RowStatus

/** Maps the shared detail values to the 문구 표 keys (pure; the shared code only classifies). */
object DetailText {
    /** The INCOMPLETE notice line (C4 spec `DetailKind` table). */
    @StringRes
    fun noticeText(notice: DetailNotice): Int = when (notice) {
        DetailNotice.INFORMATION_MISSING -> R.string.detail_incomplete_info
        DetailNotice.CATEGORY_UNDECIDED -> R.string.detail_incomplete_category
        DetailNotice.CATEGORY_DELETED -> R.string.detail_incomplete_reassign
    }

    /** "방금 저장" · "오늘 저장" · "M월 d일 저장" (another year: "y년 M월 d일 저장"). */
    fun savedText(label: SavedLabel): ResText = when (label) {
        SavedLabel.JustNow -> ResText(R.string.detail_saved_just_now)
        SavedLabel.Today -> ResText(R.string.detail_saved_today)
        is SavedLabel.OnDate -> if (label.year == null) {
            ResText(R.string.detail_saved_date, args = listOf(label.month, label.day))
        } else {
            ResText(R.string.detail_saved_date_year, args = listOf(label.year!!, label.month, label.day))
        }
    }

    /** D12: connection problems say "불러오지 못했어요", a deleted item its own line, everything else the server line. */
    @StringRes
    fun errorText(error: ClientError): Int = when (error.kind) {
        ErrorKind.NETWORK, ErrorKind.TIMEOUT -> R.string.detail_error_network
        ErrorKind.NOT_FOUND -> R.string.detail_not_found
        else -> R.string.detail_error_server
    }

    /** "N일 전 확인한 가격이에요. …" with the home row's relative time words. */
    fun priceCheckedText(time: RelativeTime): ResText = ResText(R.string.detail_price_checked, HomeRowText.time(time))

    /**
     * The purpose dot's color from the wire key (`CORAL`): its token when the lowercased key is one of
     * [PurposeKeys.colorKeys], otherwise null (the caller draws a neutral `textSecondary` dot).
     */
    fun purposeColor(colorKey: String?): WLPurposeColor? {
        val key = colorKey?.lowercase()?.takeIf { it in PurposeKeys.colorKeys } ?: return null
        return WLPurposeColor.entries.firstOrNull { it.name.lowercase() == key }
    }

    /**
     * The local screen's waiting tile caption: the home row's reason (`row_*`). A signed-out link's home
     * line carries its saved time, so its tile uses the home card's title "분석 대기" instead.
     */
    @StringRes
    fun localTileText(status: RowStatus): Int =
        if (status == RowStatus.LOCAL_ONLY) R.string.home_pending_title else HomeRowText.meta(status)

    /** The local screen's saved line: "N분 전 저장 · 이 기기에만 있어요" (every local link is still only here). */
    fun localSavedText(savedAt: RelativeTime): ResText = ResText(R.string.home_pending_meta, HomeRowText.time(savedAt))
}
