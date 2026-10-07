package app.wishlist.android.feature.home

import app.wishlist.android.R
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.presentation.RowStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every RelativeTime and RowStatus maps to its 문구 표 key (no Android Resources on the JVM). */
class HomeRowTextTest {
    @Test fun relative_times_map_to_time_keys_with_their_value() {
        assertEquals(ResText(R.string.time_just_now), HomeRowText.time(RelativeTime.JustNow))
        assertEquals(ResText(R.string.time_minutes, 5), HomeRowText.time(RelativeTime.Minutes(5)))
        assertEquals(ResText(R.string.time_hours, 3), HomeRowText.time(RelativeTime.Hours(3)))
        assertEquals(ResText(R.string.time_yesterday), HomeRowText.time(RelativeTime.Yesterday))
        assertEquals(ResText(R.string.time_days, 2), HomeRowText.time(RelativeTime.Days(2)))
    }

    @Test fun every_row_status_has_a_meta_key() {
        val expected = mapOf(
            RowStatus.LOCAL_ONLY to R.string.home_pending_meta,
            RowStatus.SENDING to R.string.row_sending,
            RowStatus.WAITING_NETWORK to R.string.row_waiting_network,
            RowStatus.FAILED to R.string.row_failed,
            RowStatus.PROCESSING to R.string.row_processing,
        )
        assertEquals(RowStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, res) -> assertEquals(status.name, res, HomeRowText.meta(status)) }
    }

    @Test fun only_the_local_row_meta_carries_the_saved_time() {
        RowStatus.entries.forEach { status ->
            assertEquals(status.name, status == RowStatus.LOCAL_ONLY, HomeRowText.metaShowsTime(status))
        }
    }
}
