package app.wishlist.android.navigation

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class WLSurfaceRegistryTest {
    private val first = WLSurfaceSpec(Color.Red, 20.dp)
    private val second = WLSurfaceSpec(Color.Blue, 8.dp)

    @Test fun destinationRefreshesThemeAndRetainsLatestAppearanceAfterDisposal() {
        val retained = SurfaceSpecFallback()
        assertEquals(first, retained.resolve(null, first))
        assertEquals(first, retained.resolve(first, second))
        assertEquals(second, retained.resolve(second, first))
        assertEquals(second, retained.resolve(null, first))
    }

    @Test fun disposingOldRegistrationDoesNotRemoveLiveReplacement() {
        val registry = WLSurfaceRegistry()
        val old = Any()
        val live = Any()
        registry.register("card", old, first)
        registry.register("card", live, second)
        registry.unregister("card", old)
        assertEquals(second, registry.specs["card"])
        registry.unregister("card", live)
        assertTrue(registry.specs.isEmpty())
    }

    @Test fun disposingReplacementRestoresStillLiveSource() {
        val registry = WLSurfaceRegistry()
        val old = Any()
        val live = Any()
        registry.register("card", old, first)
        registry.register("card", live, second)
        registry.unregister("card", live)
        assertEquals(first, registry.specs["card"])
    }

    @Test fun unchangedValueDoesNotInvalidateReaders() {
        val registry = WLSurfaceRegistry()
        val owner = Any()
        registry.register("card", owner, first)
        Snapshot.sendApplyNotifications()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { invalidations++ }) { registry.specs["card"] }
            registry.register("card", owner, first.copy())
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            registry.register("card", owner, second)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
        } finally {
            observer.stop()
        }
    }
}
