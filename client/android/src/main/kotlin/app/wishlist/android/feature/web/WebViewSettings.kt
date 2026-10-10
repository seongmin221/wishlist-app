package app.wishlist.android.feature.web

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The values the original-link web view runs with (spec §4 "Android WebView 설정"), kept as plain data so a JVM
 * test can pin them. Shops need JavaScript, DOM storage and third-party cookies (store sign-ins); because
 * third-party cookies are on, local files and content providers stay unreachable and mixed content is never
 * loaded. Safe Browsing keeps its default (on). `addJavascriptInterface` is never called anywhere: a page must
 * not reach app code (PR checklist: code search, not a lint rule).
 */
internal data class WishlistWebDefaults(
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val allowFileAccess: Boolean,
    val allowContentAccess: Boolean,
    /** false: `target=_blank` and `window.open` load in the same web view (spec §4, `window.opener` is lost). */
    val supportMultipleWindows: Boolean,
    val mixedContentMode: Int,
    val acceptThirdPartyCookies: Boolean,
) {
    companion object {
        val Wishlist = WishlistWebDefaults(
            javaScriptEnabled = true,
            domStorageEnabled = true,
            allowFileAccess = false,
            allowContentAccess = false,
            supportMultipleWindows = false,
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            acceptThirdPartyCookies = true,
        )
    }
}

/** Writes [d] into these settings (the cookie part needs the [WebView]; see [applyWishlistDefaults] on it). */
@android.annotation.SuppressLint("SetJavaScriptEnabled") // shops need scripts; no JS interface is exposed
internal fun WebSettings.applyWishlistDefaults(d: WishlistWebDefaults = WishlistWebDefaults.Wishlist) {
    javaScriptEnabled = d.javaScriptEnabled
    domStorageEnabled = d.domStorageEnabled
    allowFileAccess = d.allowFileAccess
    allowContentAccess = d.allowContentAccess
    setSupportMultipleWindows(d.supportMultipleWindows)
    mixedContentMode = d.mixedContentMode
}

/** Settings plus the cookie rule. The web view uses the default profile's cookie jar ([CookieManager.getInstance]). */
internal fun WebView.applyWishlistDefaults(d: WishlistWebDefaults = WishlistWebDefaults.Wishlist) {
    settings.applyWishlistDefaults(d)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, d.acceptThirdPartyCookies)
}
