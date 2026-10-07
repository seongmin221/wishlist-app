package app.wishlist.android.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A share is received once per share: a fresh launch receives, a process-death restore does not. */
class ShareLaunchTest {
    @Test fun fresh_launch_receives() {
        assertTrue(ShareLaunch.shouldReceive(restored = false, modelRetained = false))
    }

    @Test fun restore_after_process_death_finishes_without_receiving() {
        assertFalse(ShareLaunch.shouldReceive(restored = true, modelRetained = false))
    }

    @Test fun configuration_recreation_keeps_the_running_receive() {
        assertTrue(ShareLaunch.shouldReceive(restored = true, modelRetained = true))
    }
}
