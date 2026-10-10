package app.wishlist.android.feature.web

/** What the web view does with one navigation (spec §4 탐색 규칙). */
enum class WebDecision { LOAD_INSIDE, BLOCK, OPEN_EXTERNAL, CONFIRM_EXTERNAL }

/**
 * Pure navigation table, shared vector-for-vector with iOS `WebNavigationPolicy`.
 *
 * - main frame: `http`/`https` and `about:blank` load inside; `data:`/`blob:` (top-level phishing, Chrome's
 *   rule), `javascript:`/`file:`/`content:` and other `about:` pages are blocked.
 * - sub-frame: `http`/`https`/`data`/`blob`/`about` load inside; `javascript`/`file`/`content` are blocked.
 * - any other scheme (`intent`, `tel`, `mailto`, `market`, payment apps): an external app, at once on a user
 *   gesture, otherwise after the FWebViewExternal confirmation. An empty scheme is blocked.
 */
object WebNavigationPolicy {
    private val web = setOf("http", "https")
    private val subresourceOnly = setOf("data", "blob")
    private val neverLoaded = setOf("javascript", "file", "content")

    /** [mainFrame]: top-level navigation; [userGesture]: Android `hasGesture()`, iOS `.linkActivated`. */
    fun decide(scheme: String, mainFrame: Boolean, userGesture: Boolean, isAboutBlank: Boolean): WebDecision {
        val s = scheme.lowercase()
        return when {
            s in web -> WebDecision.LOAD_INSIDE
            s == "about" -> if (!mainFrame || isAboutBlank) WebDecision.LOAD_INSIDE else WebDecision.BLOCK
            s in subresourceOnly -> if (mainFrame) WebDecision.BLOCK else WebDecision.LOAD_INSIDE
            s in neverLoaded || s.isEmpty() -> WebDecision.BLOCK
            userGesture -> WebDecision.OPEN_EXTERNAL
            else -> WebDecision.CONFIRM_EXTERNAL
        }
    }
}
