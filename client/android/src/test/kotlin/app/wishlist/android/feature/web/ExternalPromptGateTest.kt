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
        g.onMainFrameNavigation("https://other.com/")
        assertEquals(PROMPT, g.onRequest(confirm = true))
    }

    @Test
    fun aClosedWebViewNeitherAsksNorLaunches() {
        val g = ExternalPromptGate()
        assertEquals(PROMPT, g.onRequest(confirm = true))
        assertTrue(g.mayLaunchConfirmed())
        g.close() // holder cleared while the app-wide dialog is still up
        assertTrue(g.isClosed)
        assertFalse(g.mayLaunchConfirmed())
        assertEquals(DROP, g.onRequest(confirm = false))
        assertEquals(DROP, g.onRequest(confirm = true))
        g.onPromptClosed(confirmed = true, currentUrl = page) // the late dismissal changes nothing
        assertFalse(g.mayLaunchConfirmed())
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
        g.onMainFrameNavigation("https://other.com/")
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
