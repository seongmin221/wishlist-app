package app.wishlist.android.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which text of an `ACTION_SEND` intent goes to the shared parser (JVM: the intent's fields, not the Intent). */
class ShareIntentTextTest {
    private val send = "android.intent.action.SEND"

    private fun text(action: String? = send, type: String? = "text/plain", text: CharSequence? = null, subject: CharSequence? = null) =
        ShareIntentText.from(action, type, text, subject)

    @Test fun text_with_a_link_wins_over_the_subject() {
        assertEquals("https://www.musinsa.com/p/1", text(text = "https://www.musinsa.com/p/1", subject = "[무신사] 셔츠"))
    }

    @Test fun text_alone_is_used_as_is() {
        assertEquals("[무신사] 셔츠 https://www.musinsa.com/p/1", text(text = "[무신사] 셔츠 https://www.musinsa.com/p/1"))
    }

    @Test fun subject_is_used_when_there_is_no_text() {
        assertEquals("https://shop.example/a", text(subject = "https://shop.example/a"))
        assertEquals("https://shop.example/a", text(text = "  ", subject = "https://shop.example/a"))
    }

    @Test fun subject_and_text_without_a_link_are_joined_subject_first() {
        assertEquals("https://shop.example/a 셔츠 좋아요", text(text = "셔츠 좋아요", subject = "https://shop.example/a"))
        assertEquals("셔츠 좋아요", text(text = "좋아요", subject = "셔츠"))
    }

    @Test fun uppercase_scheme_counts_as_a_link_in_text() {
        assertEquals("HTTPS://SHOP.EXAMPLE/A", text(text = "HTTPS://SHOP.EXAMPLE/A", subject = "제목"))
    }

    @Test fun nothing_to_share_is_null() {
        assertNull(text())
        assertNull(text(text = " ", subject = ""))
    }

    @Test fun other_actions_or_non_text_types_are_null() {
        assertNull(text(action = "android.intent.action.VIEW", text = "https://shop.example/a"))
        assertNull(text(action = null, text = "https://shop.example/a"))
        assertNull(text(type = "image/png", text = "https://shop.example/a"))
    }

    @Test fun missing_type_still_reads_the_text() {
        assertEquals("https://shop.example/a", text(type = null, text = "https://shop.example/a"))
    }
}
