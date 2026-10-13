package app.wishlist.android.feature.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android.content.Intent` is a stub in JVM unit tests (no Robolectric), so the sanitizer's decision runs
 * against [ParsedIntentUri], the pure `#Intent;…;end` parser. The Android adapter feeds the same
 * [IntentSanitizer.sanitize] core with a real `Intent.parseUri` result.
 */
class IntentSanitizerTest {
    private fun sanitize(uri: String) = IntentSanitizer.sanitize(ParsedIntentUri.parse(uri))

    @Test
    fun parserReadsTheIntentFields() {
        val parsed = ParsedIntentUri.parse(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
                "component=com.evil/.Steal;S.browser_fallback_url=https%3A%2F%2Fm.shop.com%2Fa%3Fq%3D%ED%95%9C;end",
        )!!
        assertEquals("zxing", parsed.dataScheme)
        assertEquals("com.google.zxing.client.android", parsed.packageName)
        assertEquals("com.evil/.Steal", parsed.component)
        assertFalse(parsed.hasSelector)
        assertEquals("https://m.shop.com/a?q=한", parsed.fallbackUrl)
    }

    @Test
    fun componentIsClearedAndBrowsableIsAdded() {
        val result = sanitize("intent://scan/#Intent;scheme=zxing;component=com.evil/.Steal;end")
        val target = (result as SanitizedIntent.External).intent
        assertNull(target.component)
        assertFalse(target.hasSelector)
        assertTrue(ParsedIntentUri.CATEGORY_BROWSABLE in target.categories)
    }

    @Test
    fun selectorAndItsComponentAreCleared() {
        val parsed = ParsedIntentUri.parse("intent:#Intent;action=android.intent.action.VIEW;SEL;component=com.evil/.Steal;end")!!
        assertTrue(parsed.hasSelector)
        assertNull(parsed.component) // the component belongs to the selector, not the main intent
        val target = (IntentSanitizer.sanitize(parsed) as SanitizedIntent.External).intent
        assertFalse(target.hasSelector)
        assertNull(target.component)
        assertTrue(ParsedIntentUri.CATEGORY_BROWSABLE in target.categories)
    }

    @Test
    fun anHttpFallbackTravelsWithTheIntentForWhenNoAppTakesIt() {
        val uri = "intent://item/1#Intent;scheme=shopapp;package=com.shop;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end"
        assertEquals("https://m.shop.com", (sanitize(uri) as SanitizedIntent.External).fallbackUrl)
    }

    @Test
    fun nonWebFallbackIsDropped() {
        listOf(
            "intent://x#Intent;scheme=shopapp;S.browser_fallback_url=javascript%3Aalert(1);end",
            "intent://x#Intent;scheme=shopapp;S.browser_fallback_url=file%3A%2F%2F%2Fetc%2Fhosts;end",
            "intent://x#Intent;scheme=shopapp;end",
        ).forEach { assertNull(it, (sanitize(it) as SanitizedIntent.External).fallbackUrl) }
    }

    @Test
    fun intentPointingAtHttpDataNeverLeavesTheWebView() {
        assertEquals(
            SanitizedIntent.Fallback("https://m.shop.com"),
            sanitize("intent://m.shop.com/a#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end"),
        )
        assertEquals(SanitizedIntent.Drop, sanitize("intent://m.shop.com/a#Intent;scheme=http;package=com.evil;end"))
        assertEquals(SanitizedIntent.Drop, sanitize("intent:https://m.shop.com/#Intent;end"))
    }

    @Test
    fun localOrScriptDataNeverGoesExternal() {
        assertEquals(
            SanitizedIntent.Fallback("https://m.shop.com"),
            sanitize("intent:///etc/hosts#Intent;scheme=file;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end"),
        )
        assertEquals(SanitizedIntent.Drop, sanitize("intent://com.app.provider/x#Intent;scheme=content;end"))
        assertEquals(SanitizedIntent.Drop, sanitize("intent:javascript:alert(1)#Intent;end"))
        assertEquals(SanitizedIntent.Drop, sanitize("intent://x#Intent;scheme=JavaScript;end"))
    }

    @Test
    fun parserExceptionsEndInFallbackOrDropInsteadOfCrashing() {
        // Intent.parseUri throws NumberFormatException for `launchFlags=q`, `i.x=zz`, bad l./f./d. extras.
        val withFallback = "intent://x#Intent;scheme=shop;launchFlags=q;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end"
        val numberFormat: (String) -> ParsedIntentUri = { throw NumberFormatException("q") }
        val illegal: (String) -> ParsedIntentUri = { throw IllegalArgumentException("bad") }
        val syntax: (String) -> ParsedIntentUri = { throw java.net.URISyntaxException(it, "bad") }
        val other: (String) -> ParsedIntentUri = { throw IndexOutOfBoundsException("odd extra") }
        for (parse in listOf(numberFormat, illegal, syntax, other)) {
            assertEquals(SanitizedIntent.Fallback("https://m.shop.com"), IntentSanitizer.sanitize(withFallback, parse))
            assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize("intent://x#Intent;i.x=zz;end", parse))
        }
    }

    @Test
    fun guardedPathHardensWhatTheParserReturns() {
        val result = IntentSanitizer.sanitize(
            "intent://scan/#Intent;scheme=zxing;component=com.evil/.Steal;SEL;component=a/.B;end",
        ) { ParsedIntentUri.parse(it)!! }
        val target = (result as SanitizedIntent.External).intent
        assertNull(target.component)
        assertFalse(target.hasSelector)
        assertTrue(ParsedIntentUri.CATEGORY_BROWSABLE in target.categories)
        assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize("intent://scan/") { ParsedIntentUri.parse(it)!! })
    }

    @Test
    fun malformedIntentUrisAreDropped() {
        listOf(
            "intent://scan/#Intent;scheme=zxing", // no end
            "intent://scan/", // no #Intent
            "intent://scan/#Other;scheme=zxing;end",
            "https://shop.com/#Intent;scheme=zxing;end",
        ).forEach { assertNull(it, ParsedIntentUri.parse(it)) }
        assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize(null))
    }

    @Test
    fun launchFlagsAreClearedIncludingUriGrants() {
        // 0x43 = FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val parsed = ParsedIntentUri.parse("intent://x/#Intent;scheme=pay;launchFlags=0x43;end")!!
        assertEquals(0x43, parsed.flags)
        val target = (IntentSanitizer.sanitize(parsed) as SanitizedIntent.External).intent
        assertEquals(0, target.flags)
    }
}
