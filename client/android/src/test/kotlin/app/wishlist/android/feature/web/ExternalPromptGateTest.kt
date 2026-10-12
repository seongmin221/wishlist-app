package app.wishlist.android.feature.web

import app.wishlist.android.feature.web.ExternalPromptGate.Action.DROP
import app.wishlist.android.feature.web.ExternalPromptGate.Action.LAUNCH
import app.wishlist.android.feature.web.ExternalPromptGate.Action.PROMPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun cancelSilencesNonGestureRequestsUntilAMainFramePageOnAnotherHost() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onPromptClosed(confirmed = false, currentUrl = page)
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation(page) // the same page reloading itself does not lift it
        assertEquals(DROP, g.onRequest(confirm = true))
        // Ruling 13: the silence is keyed on the host, so `?n=2` loops and www./case variants stay silent.
        g.onMainFrameNavigation("$page?n=2")
        g.onMainFrameNavigation("https://WWW.Shop.com/next")
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation("about:blank") // a non-web page has no host to compare
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onMainFrameNavigation("https://other.com/")
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun aClosedWebViewNeitherAsksNorLaunches() {
        val g = ExternalPromptGate()
        assertEquals(PROMPT, g.onRequest(confirm = true))
        assertFalse(g.isClosed)
        g.close() // holder cleared while the app-wide dialog is still up
        assertTrue(g.isClosed)
        assertEquals(DROP, g.onRequest(confirm = false))
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onPromptClosed(confirmed = true, currentUrl = page) // the late dismissal changes nothing
        assertTrue(g.isClosed)
    }

    @Test
    fun userTapsAlwaysLaunch() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onPromptClosed(confirmed = false, currentUrl = page)
        assertEquals(LAUNCH, g.onRequest(confirm = false))
    }

    @Test
    fun aPromptLostWithTheScreenSilencesNothing() {
        val g = ExternalPromptGate()
        g.onRequest(confirm = true)
        g.onScreenRestarted() // the overlay (and its dialog) did not survive; the user never answered
        assertEquals(PROMPT, g.onRequest(confirm = true)) // a payment page's retry asks again
    }

    @Test
    fun restartWithoutAPromptChangesNothing() {
        val g = ExternalPromptGate()
        g.onScreenRestarted()
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
