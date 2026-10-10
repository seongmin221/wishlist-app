package app.wishlist.android.feature.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Top bar and bottom bar readings of the web view state (spec §4, D6, D13, D14). */
class WebPageStateTest {
    private fun page(url: String = "https://www.musinsa.com/p/1", title: String? = null, loading: Boolean = false, progress: Int = 100) =
        WebPageState(url = url, title = title, loading = loading, progress = progress)

    @Test
    fun hostDropsWwwAndUserinfo() {
        assertEquals("musinsa.com", page().host)
        assertEquals("shop.example.com", page("https://user:pw@www.shop.example.com:8443/a").host)
    }

    @Test
    fun lockOnlyForHttps() {
        assertTrue(page("https://a.com").secure)
        assertTrue(page("HTTPS://a.com").secure)
        assertFalse(page("http://a.com").secure)
        assertFalse(page("about:blank").secure)
    }

    @Test
    fun titleMissingShowsDomainOnly() {
        assertNull(page(title = null).titleLine)
        assertNull(page(title = "  ").titleLine)
        // WebView reports the URL (with or without scheme) as the title of a page without <title>.
        assertNull(page(url = "https://a.com/x", title = "https://a.com/x").titleLine)
        assertNull(page(url = "https://a.com/x", title = "a.com/x").titleLine)
        assertEquals("상품 페이지", page(title = " 상품 페이지 ").titleLine)
    }

    @Test
    fun failedPageShowsDomainOnly() {
        val failed = page(title = "상품 페이지").copy(failed = true)
        assertNull(failed.titleLine)
        assertEquals("musinsa.com", failed.host)
    }

    @Test
    fun onlyWebUrlsReplaceTheShownPageWhenALoadStarts() {
        val shown = page(title = "상품 페이지")
        val blank = shown.started("about:blank")
        assertEquals("https://www.musinsa.com/p/1", blank.url)
        assertNull(blank.title)
        assertTrue(blank.loading)
        assertEquals("https://www.musinsa.com/p/1", shown.started(null).url)
        assertEquals("https://www.musinsa.com/p/1", shown.started("data:text/html,x").url)
        val next = shown.copy(failed = true).started("https://m.musinsa.com/p/2")
        assertEquals("https://m.musinsa.com/p/2", next.url)
        assertFalse(next.failed)
    }

    @Test
    fun aNewLoadStartsTheProgressLineOver() {
        val finished = page(title = "상품 페이지").copy(progress = 100, loading = false)
        val next = finished.started("https://m.musinsa.com/p/2")
        assertEquals(0, next.progress)
        assertTrue(next.progressVisible)
    }

    @Test
    fun progressLineHidesAtHundred() {
        assertTrue(page(loading = true, progress = 40).progressVisible)
        assertFalse(page(loading = true, progress = 100).progressVisible)
        assertFalse(page(loading = false, progress = 40).progressVisible)
    }

    @Test
    fun reloadBecomesStopWhileLoading() {
        assertTrue(page(loading = true, progress = 100).showsStop)
        assertFalse(page(loading = false).showsStop)
    }

    @Test
    fun copyNoticeOnlyBelowAndroid13() {
        assertTrue(WebShare.showsCopyNotice(32))
        assertFalse(WebShare.showsCopyNotice(33))
        assertFalse(WebShare.showsCopyNotice(36))
    }

    @Test
    fun schemeIsTheTextBeforeTheFirstColon() {
        assertEquals("intent", schemeOf("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("tel", schemeOf("tel:010"))
        assertEquals("", schemeOf("/relative:path"))
        assertEquals("", schemeOf("no-colon"))
    }
}

/** Frame-busting guard: an iframe must not put a fallback URL into the main frame without a tap. */
class WebFallbackRuleTest {
    @Test
    fun mainFrameFallbacksStay() {
        assertTrue(WebExternalApps.fallbackAllowed(mainFrame = true, userGesture = false))
        assertTrue(WebExternalApps.fallbackAllowed(mainFrame = true, userGesture = true))
    }

    @Test
    fun subFrameFallbackNeedsATap() {
        assertTrue(WebExternalApps.fallbackAllowed(mainFrame = false, userGesture = true))
        assertFalse(WebExternalApps.fallbackAllowed(mainFrame = false, userGesture = false))
    }
}
