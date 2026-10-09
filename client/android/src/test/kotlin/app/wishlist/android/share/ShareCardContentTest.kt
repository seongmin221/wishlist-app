package app.wishlist.android.share

import app.wishlist.android.R
import app.wishlist.shared.submission.ShareCardKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Card copy per kind (C3-D9 + boards FShareSaved{,Local,Offline}). */
class ShareCardContentTest {
    @Test fun every_kind_has_its_board_copy() {
        val expected = mapOf(
            ShareCardKind.SAVED to (R.string.share_saved_title to R.string.share_saved_fetching),
            ShareCardKind.LOCAL to (R.string.share_local_title to R.string.share_local_line),
            ShareCardKind.OFFLINE to (R.string.share_local_title to R.string.share_offline_line),
            ShareCardKind.INVALID to (R.string.share_invalid_title to R.string.share_invalid_line),
            ShareCardKind.STORE_FAILED to (R.string.share_failed_title to R.string.share_failed_line),
            ShareCardKind.DEFERRED to (R.string.share_saved_title to R.string.share_saved_open_app),
        )
        assertEquals(ShareCardKind.entries.toSet(), expected.keys)
        expected.forEach { (kind, copy) ->
            val content = ShareCardContent.of(kind)
            assertEquals(kind.name, copy, content.title to content.line)
        }
    }

    @Test fun tones_follow_the_boards() {
        assertEquals(ShareCardTone.DONE, ShareCardContent.of(ShareCardKind.SAVED).tone)
        assertEquals(ShareCardTone.PENDING, ShareCardContent.of(ShareCardKind.LOCAL).tone)
        assertEquals(ShareCardTone.OFFLINE, ShareCardContent.of(ShareCardKind.OFFLINE).tone)
        assertEquals(ShareCardTone.FAILED, ShareCardContent.of(ShareCardKind.INVALID).tone)
        assertEquals(ShareCardTone.FAILED, ShareCardContent.of(ShareCardKind.STORE_FAILED).tone)
    }
}
