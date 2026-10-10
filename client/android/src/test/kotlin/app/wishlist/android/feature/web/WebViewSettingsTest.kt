package app.wishlist.android.feature.web

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §4 "Android WebView 설정": the values `applyWishlistDefaults()` writes (no Robolectric, so the data is tested). */
class WebViewSettingsTest {
    private val d = WishlistWebDefaults.Wishlist

    @Test
    fun localFilesAndContentProvidersAreUnreachable() {
        assertFalse(d.allowFileAccess)
        assertFalse(d.allowContentAccess)
    }

    @Test
    fun newWindowsOpenInTheSameWebView() {
        assertFalse(d.supportMultipleWindows)
    }

    @Test
    fun mixedContentIsNeverAllowed() {
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, d.mixedContentMode)
    }

    @Test
    fun shopsGetScriptsStorageAndThirdPartyCookies() {
        assertTrue(d.javaScriptEnabled)
        assertTrue(d.domStorageEnabled)
        assertTrue(d.acceptThirdPartyCookies)
    }
}
