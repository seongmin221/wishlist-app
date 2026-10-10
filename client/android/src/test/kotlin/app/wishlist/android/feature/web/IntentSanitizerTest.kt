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
    private val always: (ParsedIntentUri) -> Boolean = { true }
    private val never: (ParsedIntentUri) -> Boolean = { false }

    private fun sanitize(uri: String, resolvable: (ParsedIntentUri) -> Boolean) =
        IntentSanitizer.sanitize(ParsedIntentUri.parse(uri), resolvable)

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
        val result = sanitize("intent://scan/#Intent;scheme=zxing;component=com.evil/.Steal;end", always)
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
        val target = (IntentSanitizer.sanitize(parsed, always) as SanitizedIntent.External).intent
        assertFalse(target.hasSelector)
        assertNull(target.component)
        assertTrue(ParsedIntentUri.CATEGORY_BROWSABLE in target.categories)
    }

    @Test
    fun resolvabilityIsCheckedOnTheSanitizedIntent() {
        var seenComponent: String? = "unset"
        var seenSelector = true
        sanitize("intent://x/#Intent;scheme=pay;component=com.evil/.Steal;SEL;component=a/.B;end") {
            seenComponent = it.component
            seenSelector = it.hasSelector
            true
        }
        assertNull(seenComponent)
        assertFalse(seenSelector)
    }

    @Test
    fun unresolvableIntentOpensAnHttpFallbackInside() {
        val uri = "intent://item/1#Intent;scheme=shopapp;package=com.shop;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end"
        assertEquals(SanitizedIntent.Fallback("https://m.shop.com"), sanitize(uri, never))
    }

    @Test
    fun nonWebFallbackIsDropped() {
        assertEquals(
            SanitizedIntent.Drop,
            sanitize("intent://x#Intent;scheme=shopapp;S.browser_fallback_url=javascript%3Aalert(1);end", never),
        )
        assertEquals(
            SanitizedIntent.Drop,
            sanitize("intent://x#Intent;scheme=shopapp;S.browser_fallback_url=file%3A%2F%2F%2Fetc%2Fhosts;end", never),
        )
        assertEquals(SanitizedIntent.Drop, sanitize("intent://x#Intent;scheme=shopapp;end", never))
    }

    @Test
    fun intentPointingAtHttpDataNeverLeavesTheWebView() {
        var asked = false
        val probe: (ParsedIntentUri) -> Boolean = { asked = true; true }
        assertEquals(
            SanitizedIntent.Fallback("https://m.shop.com"),
            sanitize("intent://m.shop.com/a#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end", probe),
        )
        assertEquals(SanitizedIntent.Drop, sanitize("intent://m.shop.com/a#Intent;scheme=http;package=com.evil;end", probe))
        assertEquals(SanitizedIntent.Drop, sanitize("intent:https://m.shop.com/#Intent;end", probe))
        assertFalse(asked)
    }

    @Test
    fun localOrScriptDataNeverGoesExternal() {
        var asked = false
        val probe: (ParsedIntentUri) -> Boolean = { asked = true; true }
        assertEquals(
            SanitizedIntent.Fallback("https://m.shop.com"),
            sanitize("intent:///etc/hosts#Intent;scheme=file;S.browser_fallback_url=https%3A%2F%2Fm.shop.com;end", probe),
        )
        assertEquals(SanitizedIntent.Drop, sanitize("intent://com.app.provider/x#Intent;scheme=content;end", probe))
        assertEquals(SanitizedIntent.Drop, sanitize("intent:javascript:alert(1)#Intent;end", probe))
        assertEquals(SanitizedIntent.Drop, sanitize("intent://x#Intent;scheme=JavaScript;end", probe))
        assertFalse(asked)
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
            assertEquals(SanitizedIntent.Fallback("https://m.shop.com"), IntentSanitizer.sanitize(withFallback, parse, always))
            assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize("intent://x#Intent;i.x=zz;end", parse, always))
        }
    }

    @Test
    fun guardedPathHardensWhatTheParserReturns() {
        val result = IntentSanitizer.sanitize(
            "intent://scan/#Intent;scheme=zxing;component=com.evil/.Steal;SEL;component=a/.B;end",
            { ParsedIntentUri.parse(it)!! },
            always,
        )
        val target = (result as SanitizedIntent.External).intent
        assertNull(target.component)
        assertFalse(target.hasSelector)
        assertTrue(ParsedIntentUri.CATEGORY_BROWSABLE in target.categories)
        assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize("intent://scan/", { ParsedIntentUri.parse(it)!! }, always))
    }

    @Test
    fun malformedIntentUrisAreDropped() {
        listOf(
            "intent://scan/#Intent;scheme=zxing", // no end
            "intent://scan/", // no #Intent
            "intent://scan/#Other;scheme=zxing;end",
            "https://shop.com/#Intent;scheme=zxing;end",
        ).forEach { assertNull(it, ParsedIntentUri.parse(it)) }
        assertEquals(SanitizedIntent.Drop, IntentSanitizer.sanitize(null, always))
    }

    @Test
    fun launchFlagsAreClearedIncludingUriGrants() {
        // 0x43 = FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val parsed = ParsedIntentUri.parse("intent://x/#Intent;scheme=pay;launchFlags=0x43;end")!!
        assertEquals(0x43, parsed.flags)
        val target = (IntentSanitizer.sanitize(parsed, always) as SanitizedIntent.External).intent
        assertEquals(0, target.flags)
    }

    @Test
    fun flagsAreClearedBeforeResolvabilityIsAsked() {
        var seen = -1
        sanitize("intent://x/#Intent;scheme=pay;launchFlags=0x10000000;end") { seen = it.flags; true }
        assertEquals(0, seen)
    }
}
