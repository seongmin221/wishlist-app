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

    @Test fun processing_row_uses_the_board_extracting_key() {
        // Ruling 14: board FHome "상품 정보 추출 중" lives under row_processing.
        assertEquals(R.string.row_processing, HomeRowText.meta(RowStatus.PROCESSING))
    }

    @Test fun logged_in_caption_counts_the_sorting_rows() {
        // Ruling 13: board FHome header caption "할 일 N개".
        assertEquals(ResText(R.string.home_todo_count, 0), HomeRowText.todoCount(0))
        assertEquals(ResText(R.string.home_todo_count, 7), HomeRowText.todoCount(7))
    }

    @Test fun only_the_local_row_meta_carries_the_saved_time() {
        RowStatus.entries.forEach { status ->
            assertEquals(status.name, status == RowStatus.LOCAL_ONLY, HomeRowText.metaShowsTime(status))
        }
    }
}
