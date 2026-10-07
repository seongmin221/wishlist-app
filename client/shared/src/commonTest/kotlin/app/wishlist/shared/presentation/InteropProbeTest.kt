package app.wishlist.shared.presentation

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InteropProbeTest {
    @Test
    fun flowPublishesIncrementedState() = runTest {
        val probe = InteropProbe()
        try {
            probe.state.test {
                assertEquals(0, awaitItem())
                assertEquals(1, probe.increment())
                assertEquals(1, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            probe.close()
        }
    }

    @Test
    fun closedProbeRejectsFurtherWork() = runTest {
        val probe = InteropProbe()
        probe.close()
        assertFailsWith<CancellationException> { probe.increment() }
        assertEquals(0, probe.state.value)
    }
}
