package app.wishlist.android.feature.web

import app.wishlist.shared.domain.DisplayFormat

/**
 * What the FWebView bars show for the current page (spec §4, D13, D14). [url] is the page's current URL (the
 * share sheet uses it, D6), [progress] WebChromeClient's 0–100, [loading] between `onPageStarted` and
 * `onPageFinished`, [failed] a main-frame load error (sub-resource errors never set it).
 */
internal data class WebPageState(
    val url: String,
    val title: String? = null,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val failed: Boolean = false,
) {
    /** `www.`-less host without userinfo or port, like home (D14). */
    val host: String get() = DisplayFormat.host(url)

    /** Lock icon only for https (D14). */
    val secure: Boolean get() = url.startsWith("https:", ignoreCase = true)

    /** Second line; null (domain only, D13) when the page has no title or WebView reports the URL as one. */
    val titleLine: String?
        get() {
            val t = title?.trim().orEmpty()
            if (t.isEmpty() || t == url || t == url.substringAfter("://")) return null
            return t
        }

    /** The 2px line hides once the load reaches 100 (D13). */
    val progressVisible: Boolean get() = loading && progress < 100

    /** Reload turns into stop while loading (D13). */
    val showsStop: Boolean get() = loading
}
