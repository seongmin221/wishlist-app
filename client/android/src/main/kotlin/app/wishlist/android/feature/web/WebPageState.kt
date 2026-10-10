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

    /**
     * Second line; null (domain only, D13) when the page has no title, WebView reports the URL as one, or the
     * load failed (the failure overlay covers the page, so its title would be stale).
     */
    val titleLine: String?
        get() {
            if (failed) return null
            val t = title?.trim().orEmpty()
            if (t.isEmpty() || t == url || t == url.substringAfter("://")) return null
            return t
        }

    /**
     * `onPageStarted` of the main frame: a new load with no title yet. Only a web URL replaces [url] (like iOS
     * `adopt(currentURL:)`), so `about:blank` or a null URL keeps the bar and the share sheet on the last page.
     */
    fun started(startedUrl: String?): WebPageState = copy(
        url = startedUrl?.takeIf(WebUrl::isWeb) ?: url,
        title = null,
        loading = true,
        failed = false,
    )

    /** The 2px line hides once the load reaches 100 (D13). */
    val progressVisible: Boolean get() = loading && progress < 100

    /** Reload turns into stop while loading (D13). */
    val showsStop: Boolean get() = loading
}
