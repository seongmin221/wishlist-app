package app.wishlist.android.feature.web

import app.wishlist.android.feature.web.ExternalPromptGate.Action.DROP
import app.wishlist.android.feature.web.ExternalPromptGate.Action.LAUNCH
import app.wishlist.android.feature.web.ExternalPromptGate.Action.PROMPT
import org.junit.Assert.assertEquals
import org.junit.Test

/** A page must not flood FWebViewExternal (e.g. `setInterval(() => location = 'market://…', 200)`). */
class ExternalPromptGateTest {
    private val page = "https://shop.com/pay"

    @Test
    fun oneConfirmationAtATime() {
        val g = ExternalPromptGate()
        assertEquals(PROMPT, g.onRequest(confirm = true))
        assertEquals(DROP, g.onRequest(confirm = true))
        assertEquals(DROP, g.onRequest(confirm = true))
    }

    @Test
    fun afterConfirmTheNextRequestMayAskAgain() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onPromptClosed(confirmed = true, currentUrl = page)
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun cancelSilencesNonGestureRequestsUntilANewMainFramePage() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onPromptClosed(confirmed = false, currentUrl = page)
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation(page) // the same page reloading itself does not lift it
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation("https://shop.com/next")
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun userTapsAlwaysLaunch() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onPromptClosed(confirmed = false, currentUrl = page)
        assertEquals(LAUNCH, g.onRequest(confirm = false))
    }

    @Test
    fun aPromptLostWithTheScreenCountsAsCancelled() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onScreenRestarted(currentUrl = page) // the overlay (and its dialog) did not survive
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation("https://shop.com/next")
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun restartWithoutAPromptChangesNothing() {
        val g = ExternalPromptGate()
        g.onScreenRestarted(currentUrl = page)
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun aPromptTheOverlayRefusedDoesNotBlockTheNextOne() {
        val g = ExternalPromptGate()
        assertEquals(PROMPT, g.onRequest(confirm = true))
        g.onPromptNotShown()
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }
}
