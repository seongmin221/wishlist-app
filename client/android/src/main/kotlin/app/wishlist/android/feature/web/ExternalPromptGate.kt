package app.wishlist.android.feature.web

/**
 * Keeps a page from flooding FWebViewExternal (spec §4, D16). A request without a user tap is asked about
 * at most once at a time; once the user cancels, further non-gesture requests are dropped until the main
 * frame starts a different page. Taps always launch (D16). Main thread only; lives with the web view holder.
 */
internal class ExternalPromptGate {
    enum class Action { LAUNCH, PROMPT, DROP }

    private var prompting = false

    /** The main-frame URL the user cancelled on; non-null while non-gesture requests are silenced. */
    private var silencedOn: String? = null

    /** [confirm]: the request needs FWebViewExternal (no user gesture). */
    fun onRequest(confirm: Boolean): Action = when {
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
        if (!confirmed) silencedOn = currentUrl
    }

    /** `showDialog` refused (another overlay was opening): nothing was asked, nothing is silenced. */
    fun onPromptNotShown() {
        prompting = false
    }

    /** `onPageStarted` of the main frame; reloading the page that was cancelled on does not lift the silence. */
    fun onMainFrameNavigation(url: String) {
        if (silencedOn != null && url != silencedOn) silencedOn = null
    }

    /** The screen was composed anew (Activity recreated): a dialog that was up is gone without an answer. */
    fun onScreenRestarted(currentUrl: String) {
        onPromptClosed(confirmed = false, currentUrl = currentUrl)
    }
}
