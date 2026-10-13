package app.wishlist.android.feature.web

import android.view.KeyEvent
import android.view.MotionEvent

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

/**
 * D16 one tap, one decision (iOS `WebViewModel.decide` clears `lastTouch` the same way). Chromium's `hasGesture()`
 * stays true for every navigation during a tap's user activation, so one tap could otherwise open several apps
 * that a script clicks after it. The first frame navigation decided with the gesture spends it, whatever it opens
 * (a page link, an app, a blocked URL; a server redirect that follows is a new decision and asks). A same-page
 * `#fragment` link never reaches `shouldOverrideUrlLoading`, so it leaves the tap (iOS skips it too).
 *
 * A new user action on the web view gives a new one: a single finger lifted within [slop] of where it went down
 * (a scroll is not one), a key pressed (keyboard, D-pad; not back or escape), or an accessibility click (TalkBack,
 * Switch Access), which reach Chromium without touches.
 */
internal class WebTapBudget(private val slop: Float) {
    private var spent = false
    private var tracking = false
    private var downX = 0f
    private var downY = 0f

    /** One `MotionEvent` (`actionMasked`, view coordinates). */
    fun onTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                downX = x
                downY = y
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !near(x, y)) tracking = false
            MotionEvent.ACTION_UP -> {
                if (tracking && near(x, y)) spent = false
                tracking = false
            }
            // A second finger or a cancelled gesture is no tap.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    /** Chromium activates on key down (a link opens on Enter's down). */
    fun onKeyDown(keyCode: Int) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) spent = false
    }

    fun onAccessibilityClick() {
        spent = false
    }

    /** The gesture one navigation may use: [hasGesture] once per user action. */
    fun use(hasGesture: Boolean): Boolean {
        if (!hasGesture || spent) return false
        spent = true
        return true
    }

    private fun near(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy <= slop * slop
    }
}

/** What 새로고침 / "다시 시도" does with the holder's view. */
internal enum class WebReloadStep {
    /** No view yet (dropped after a renderer crash, not attached again): load the page once it attaches. */
    QUEUE,

    /** A view without a page (the fresh one after a crash, or a first load that failed before it had a URL). */
    OPEN_PAGE_URL,

    RELOAD;

    companion object {
        fun of(hasView: Boolean, viewUrl: String?): WebReloadStep = when {
            !hasView -> QUEUE
            viewUrl == null -> OPEN_PAGE_URL
            else -> RELOAD
        }
    }
}
