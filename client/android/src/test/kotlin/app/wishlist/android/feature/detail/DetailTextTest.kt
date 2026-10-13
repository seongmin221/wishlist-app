package app.wishlist.android.feature.detail

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every detail value maps to its 문구 표 key (no Android Resources on the JVM). */
class DetailTextTest {
    @Test fun every_notice_has_its_sentence() {
        val expected = mapOf(
            DetailNotice.INFORMATION_MISSING to R.string.detail_incomplete_info,
            DetailNotice.CATEGORY_UNDECIDED to R.string.detail_incomplete_category,
            DetailNotice.CATEGORY_DELETED to R.string.detail_incomplete_reassign,
        )
        assertEquals(DetailNotice.entries.toSet(), expected.keys)
        expected.forEach { (notice, res) -> assertEquals(notice.name, res, DetailText.noticeText(notice)) }
    }

    @Test fun saved_labels_map_to_saved_keys_with_their_date() {
        assertEquals(ResText(R.string.detail_saved_just_now), DetailText.savedText(SavedLabel.JustNow))
        assertEquals(ResText(R.string.detail_saved_today), DetailText.savedText(SavedLabel.Today))
        assertEquals(ResText(R.string.detail_saved_date, args = listOf(9, 28)), DetailText.savedText(SavedLabel.OnDate(null, 9, 28)))
        assertEquals(
            ResText(R.string.detail_saved_date_year, args = listOf(2025, 12, 31)),
            DetailText.savedText(SavedLabel.OnDate(2025, 12, 31)),
        )
    }

    @Test fun every_error_kind_has_a_sentence() {
        ErrorKind.entries.forEach { kind ->
            val expected = when (kind) {
                ErrorKind.NETWORK, ErrorKind.TIMEOUT -> R.string.detail_error_network
                ErrorKind.NOT_FOUND -> R.string.detail_not_found
                else -> R.string.detail_error_server
            }
            assertEquals(kind.name, expected, DetailText.errorText(ClientError(kind)))
        }
    }

    @Test fun price_checked_time_uses_the_time_keys() {
        assertEquals(
            ResText(R.string.detail_price_checked, ResText(R.string.time_days, 2)),
            DetailText.priceCheckedText(RelativeTime.Days(2)),
        )
        assertEquals(
            ResText(R.string.detail_price_checked, ResText(R.string.time_just_now)),
            DetailText.priceCheckedText(RelativeTime.JustNow),
        )
    }

    @Test fun purpose_color_reads_the_wire_key_and_falls_back_to_neutral() {
        assertEquals(WLPurposeColor.Coral, DetailText.purposeColor("CORAL"))
        assertEquals(WLPurposeColor.Periwinkle, DetailText.purposeColor("periwinkle"))
        PurposeKeys.colorKeys.forEach { key ->
            assertEquals(key, key, DetailText.purposeColor(key.uppercase())?.name?.lowercase())
        }
        assertNull(DetailText.purposeColor(null))
        assertNull(DetailText.purposeColor("TEAL"))
        assertNull(DetailText.purposeColor(""))
    }

    @Test fun local_tile_says_why_the_link_waits() {
        RowStatus.entries.forEach { status ->
            val expected = if (status == RowStatus.LOCAL_ONLY) R.string.home_pending_title else HomeRowText.meta(status)
            assertEquals(status.name, expected, DetailText.localTileText(status))
        }
    }

    @Test fun local_saved_line_is_the_home_pending_line() {
        assertEquals(
            ResText(R.string.home_pending_meta, ResText(R.string.time_minutes, 3)),
            DetailText.localSavedText(RelativeTime.Minutes(3)),
        )
    }
}
