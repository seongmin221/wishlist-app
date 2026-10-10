package app.wishlist.android.feature.web

import app.wishlist.android.feature.web.WebDecision.BLOCK
import app.wishlist.android.feature.web.WebDecision.CONFIRM_EXTERNAL
import app.wishlist.android.feature.web.WebDecision.LOAD_INSIDE
import app.wishlist.android.feature.web.WebDecision.OPEN_EXTERNAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive scheme × frame × gesture table (spec §4). The same vectors live in iOS
 * `WebNavigationPolicyTests`; change both together.
 */
class WebNavigationPolicyTest {
    /** scheme, isAboutBlank, main-frame decision, sub-frame decision; null = external (gesture-dependent). */
    private val table: List<Row> = listOf(
        Row("http", false, LOAD_INSIDE, LOAD_INSIDE),
        Row("https", false, LOAD_INSIDE, LOAD_INSIDE),
        Row("HTTPS", false, LOAD_INSIDE, LOAD_INSIDE),
        Row("about", true, LOAD_INSIDE, LOAD_INSIDE),
        Row("about", false, BLOCK, LOAD_INSIDE),
        Row("data", false, BLOCK, BLOCK),
        Row("blob", false, BLOCK, BLOCK),
        Row("javascript", false, BLOCK, BLOCK),
        Row("JavaScript", false, BLOCK, BLOCK),
        Row("file", false, BLOCK, BLOCK),
        Row("content", false, BLOCK, BLOCK),
        Row("", false, BLOCK, BLOCK),
        Row(" javascript", false, BLOCK, BLOCK),
        Row("java\tscript", false, BLOCK, BLOCK),
        Row("javascript ", false, BLOCK, BLOCK),
        Row("1http", false, BLOCK, BLOCK),
        Row("-x", false, BLOCK, BLOCK),
        Row("tel ", false, BLOCK, BLOCK),
        Row("intent", false, null, null),
        Row("tel", false, null, null),
        Row("mailto", false, null, null),
        Row("market", false, null, null),
        Row("itms-apps", false, null, null),
        Row("kakaotalk", false, null, null),
        Row("ispmobile", false, null, null),
        Row("com.shop.app+pay-1", false, null, null),
    )

    @Test
    fun everySchemeFrameAndGestureCombinationMatchesTheTable() {
        for (row in table) for (mainFrame in listOf(true, false)) for (gesture in listOf(true, false)) {
            val fixed = if (mainFrame) row.main else row.sub
            val expected = fixed ?: if (gesture) OPEN_EXTERNAL else CONFIRM_EXTERNAL
            assertEquals(
                "${row.scheme} blank=${row.isAboutBlank} main=$mainFrame gesture=$gesture",
                expected,
                WebNavigationPolicy.decide(row.scheme, mainFrame, gesture, row.isAboutBlank),
            )
        }
    }

    @Test
    fun topLevelDataUrlIsBlockedEvenWithAGesture() {
        assertEquals(BLOCK, WebNavigationPolicy.decide("data", mainFrame = true, userGesture = true, isAboutBlank = false))
    }

    @Test
    fun aboutBlankIgnoresQueryAndFragmentOnly() {
        listOf("about:blank", "about:blank#x", "about:blank?x", "ABOUT:BLANK", "about:blank?a#b")
            .forEach { assertTrue(it, isAboutBlank(it)) }
        listOf("about:srcdoc", "about:blanks", "about:", "about:blank/x", "https://about:blank", "blank", "")
            .forEach { assertFalse(it, isAboutBlank(it)) }
        // srcdoc iframes still load inside through the policy; a top-level srcdoc does not.
        assertEquals(LOAD_INSIDE, WebNavigationPolicy.decide("about", false, false, isAboutBlank("about:srcdoc")))
        assertEquals(BLOCK, WebNavigationPolicy.decide("about", true, true, isAboutBlank("about:srcdoc")))
        assertEquals(LOAD_INSIDE, WebNavigationPolicy.decide("about", true, false, isAboutBlank("about:blank#x")))
    }

    private data class Row(val scheme: String, val isAboutBlank: Boolean, val main: WebDecision?, val sub: WebDecision?)
}
