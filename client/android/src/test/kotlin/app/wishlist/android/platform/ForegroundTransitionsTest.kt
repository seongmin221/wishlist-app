package app.wishlist.android.platform

import app.wishlist.shared.submission.FlushTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** App start/foreground signals from started-activity counts (the share card Activity excluded). */
class ForegroundTransitionsTest {
    private val transitions = ForegroundTransitions()

    private fun start(share: Boolean = false) = transitions.onStarted(isShareActivity = share)
    private fun stop(share: Boolean = false, changingConfigurations: Boolean = false) =
        transitions.onStopped(isShareActivity = share, changingConfigurations = changingConfigurations)

    @Test fun first_start_is_launch_once() {
        assertEquals(FlushTrigger.LAUNCH, start())
        assertNull(start()) // a second activity on top: still the same foreground
    }

    @Test fun background_then_foreground_is_foreground() {
        assertEquals(FlushTrigger.LAUNCH, start())
        stop()
        assertEquals(FlushTrigger.FOREGROUND, start())
        stop()
        assertEquals(FlushTrigger.FOREGROUND, start())
    }

    @Test fun configuration_change_stop_start_is_nothing() {
        assertEquals(FlushTrigger.LAUNCH, start())
        stop(changingConfigurations = true)
        assertNull(start())
        stop()
        assertEquals(FlushTrigger.FOREGROUND, start())
    }

    @Test fun share_activity_is_ignored() {
        // A process started by a share: the card is not "the app", so it neither launches nor counts.
        assertNull(start(share = true))
        stop(share = true)
        assertEquals(FlushTrigger.LAUNCH, start())
        assertNull(start(share = true))
        stop(share = true)
        stop()
        assertEquals(FlushTrigger.FOREGROUND, start())
    }
}
