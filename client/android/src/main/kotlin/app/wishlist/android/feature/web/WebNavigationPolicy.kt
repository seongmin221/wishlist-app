package app.wishlist.android.feature.web

/** What the web view does with one navigation (spec §4 탐색 규칙). */
enum class WebDecision { LOAD_INSIDE, BLOCK, OPEN_EXTERNAL, CONFIRM_EXTERNAL }

/**
 * Pure navigation table, shared vector-for-vector with iOS `WebNavigationPolicy`. It only sees frame
 * navigations (main frame or iframe); images/scripts never reach it.
 *
 * - main frame: `http`/`https` and `about:blank` load inside; other `about:` pages, `data:`/`blob:` (top-level
 *   phishing, Chrome's rule), `javascript:`/`file:`/`content:` are blocked.
 * - sub-frame (iframe): `http`/`https` and `about:` (`about:blank`, `about:srcdoc`) load inside;
 *   `data:`/`blob:` are blocked too (spec §4: allowed for sub-resources only, not iframes), as are
 *   `javascript:`/`file:`/`content:`.
 * - a scheme that is not RFC 3986 `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (empty, spaces, tabs) is blocked.
 * - any other scheme (`intent`, `tel`, `mailto`, `market`, payment apps): an external app, at once on a user
 *   gesture, otherwise after the FWebViewExternal confirmation.
 */
object WebNavigationPolicy {
    private val web = setOf("http", "https")
    private val neverLoaded = setOf("data", "blob", "javascript", "file", "content")
    private val schemeGrammar = Regex("[a-z][a-z0-9+.-]*")

    /** [mainFrame]: top-level navigation; [userGesture]: Android `hasGesture()`, iOS `.linkActivated`. */
    fun decide(scheme: String, mainFrame: Boolean, userGesture: Boolean, isAboutBlank: Boolean): WebDecision {
        val s = scheme.lowercase()
        return when {
            !schemeGrammar.matches(s) -> WebDecision.BLOCK
            s in web -> WebDecision.LOAD_INSIDE
            s == "about" -> if (!mainFrame || isAboutBlank) WebDecision.LOAD_INSIDE else WebDecision.BLOCK
            s in neverLoaded -> WebDecision.BLOCK
            userGesture -> WebDecision.OPEN_EXTERNAL
            else -> WebDecision.CONFIRM_EXTERNAL
        }
    }
}

/** `about:blank` with an optional `?query`/`#fragment` (`about:blank#x` → true, `about:srcdoc` → false). */
fun isAboutBlank(url: String): Boolean {
    if (!url.startsWith("about:", ignoreCase = true)) return false
    return url.substring("about:".length).takeWhile { it != '?' && it != '#' }.equals("blank", ignoreCase = true)
}

/**
 * The scheme [WebNavigationPolicy.decide] reads: the text before the first `:`, or "" when a `/ ? #` comes first
 * or there is no colon (the policy blocks ""). Case is kept; the policy lowercases.
 */
fun schemeOf(url: String): String {
    val end = url.indexOfFirst { it == ':' || it == '/' || it == '?' || it == '#' }
    return if (end > 0 && url[end] == ':') url.substring(0, end) else ""
}
