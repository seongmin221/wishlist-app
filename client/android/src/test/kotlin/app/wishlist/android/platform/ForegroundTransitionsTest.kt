package app.wishlist.android.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** App start/foreground signals from started-activity counts (the share card Activity excluded). */
class ForegroundTransitionsTest {
    private val transitions = ForegroundTransitions()

    private fun start(share: Boolean = false) = transitions.onStarted(isShareActivity = share)
    private fun stop(share: Boolean = false, changingConfigurations: Boolean = false) =
        transitions.onStopped(isShareActivity = share, changingConfigurations = changingConfigurations)

    @Test fun first_start_is_a_foreground_once() {
        assertTrue(start())
        assertFalse(start()) // a second activity on top: still the same foreground
    }

    @Test fun background_then_start_is_a_foreground_each_time() {
        assertTrue(start())
        stop()
        assertTrue(start())
        stop()
        assertTrue(start())
    }

    @Test fun configuration_change_stop_start_is_nothing() {
        assertTrue(start())
        stop(changingConfigurations = true)
        assertFalse(start())
        stop()
        assertTrue(start())
    }

    @Test fun share_activity_is_ignored() {
        // A process started by a share: the card is not "the app", so it neither foregrounds nor counts.
        assertFalse(start(share = true))
        stop(share = true)
        assertTrue(start())
        assertFalse(start(share = true))
        stop(share = true)
        stop()
        assertTrue(start())
    }
}
