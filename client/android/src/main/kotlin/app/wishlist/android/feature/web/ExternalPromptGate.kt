package app.wishlist.android.feature.web

import app.wishlist.shared.domain.DisplayFormat

/**
 * Keeps a page from flooding FWebViewExternal (spec §4, D16). A request without a user tap is asked about
 * at most once at a time; once the user cancels, further non-gesture requests are dropped until the main
 * frame starts a page on a different host (Ruling 13: lowercased, `www.`-less, like the top bar). Taps always
 * launch (D16). After [close] (the web view is gone) nothing launches or asks. Main thread only; lives with the
 * web view holder.
 */
internal class ExternalPromptGate {
    enum class Action { LAUNCH, PROMPT, DROP }

    private var prompting = false

    /** The main-frame host the user cancelled on; non-null while non-gesture requests are silenced. */
    private var silencedOn: String? = null

    /** The web view was closed (holder cleared): a dialog still up must not launch for the gone page. */
    var isClosed = false
        private set

    /** [confirm]: the request needs FWebViewExternal (no user gesture). */
    fun onRequest(confirm: Boolean): Action = when {
        isClosed -> Action.DROP
        !confirm -> Action.LAUNCH
        prompting || silencedOn != null -> Action.DROP
        else -> {
            prompting = true
            Action.PROMPT
        }
    }

    /** The dialog closed: 열기 ([confirmed]) or 취소 / back. */
    fun onPromptClosed(confirmed: Boolean, currentUrl: String) {
        if (!prompting) return
        prompting = false
        if (!confirmed) silencedOn = DisplayFormat.host(currentUrl)
    }

    /** `showDialog` refused (another overlay was opening): nothing was asked, nothing is silenced. */
    fun onPromptNotShown() {
        prompting = false
    }

    /** `onPageStarted` of the main frame; another page on the cancelled-on host does not lift the silence. */
    fun onMainFrameNavigation(url: String) {
        if (silencedOn != null && DisplayFormat.host(url) != silencedOn) silencedOn = null
    }

    /** 열기 of a dialog that is still up: launch only while the web view exists. */
    fun mayLaunchConfirmed(): Boolean = !isClosed

    /** `WebViewHolder.onCleared`: the page is gone. */
    fun close() {
        isClosed = true
        prompting = false
    }

    /** The screen was composed anew (Activity recreated): a dialog that was up is gone without an answer. */
    fun onScreenRestarted(currentUrl: String) {
        onPromptClosed(confirmed = false, currentUrl = currentUrl)
    }
}
