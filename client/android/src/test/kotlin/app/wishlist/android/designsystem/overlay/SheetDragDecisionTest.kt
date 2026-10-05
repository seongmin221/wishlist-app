package app.wishlist.android.designsystem.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class SheetDragDecisionTest {
    @Test fun dismissesAtQuarterOfHeight() = assertTrue(shouldDismissSheet(100f, 400f, 0f))
    @Test fun keepsBelowThresholds() = assertFalse(shouldDismissSheet(99f, 400f, 999f))
    @Test fun dismissesOnFastFling() = assertTrue(shouldDismissSheet(10f, 400f, 1000f))
}

class SheetVelocityUnitTest {
    // Compose draggable은 px/s를 준다. 토큰 1000은 dp/s다.
    @Test fun pxVelocityIsConvertedToDpBeforeDeciding() {
        // 1500px/s @ 2.75x = 약 545dp/s: 느리다.
        assertEquals(SheetDragEnd.SnapBack, sheetDragEndPx(OverlayPhase.Open, 10f, 1100f, 1500f, 2.75f))
        // 3000px/s @ 2.75x = 약 1091dp/s: 빠르다.
        assertEquals(SheetDragEnd.Dismiss, sheetDragEndPx(OverlayPhase.Open, 10f, 1100f, 3000f, 2.75f))
        // 1x에서는 px = dp.
        assertEquals(SheetDragEnd.Dismiss, sheetDragEndPx(OverlayPhase.Open, 10f, 400f, 1000f, 1f))
    }

    @Test fun distanceStaysInPx() =
        assertEquals(SheetDragEnd.Dismiss, sheetDragEndPx(OverlayPhase.Open, 275f, 1100f, 0f, 2.75f))
}

class SheetMaxHeightTest {
    @Test fun capsAt760OnTallScreens() = assertEquals(760.dp, sheetMaxHeight(1000.dp, 40.dp))
    @Test fun leavesTopInsetAndMarginOnShortScreens() = assertEquals(600.dp - 40.dp - 24.dp, sheetMaxHeight(600.dp, 40.dp))
    @Test fun neverNegative() = assertEquals(0.dp, sheetMaxHeight(10.dp, 40.dp))
}

class SheetDragEndTest {
    @Test fun dismissThenCancelledDragDoesNothing() {
        // 끄는 중 뒤로로 닫기가 시작되면 끌기가 속도 0으로 취소된다. 이때 되돌림이 나가면 안 된다.
        assertEquals(SheetDragEnd.Ignore, sheetDragEnd(OverlayPhase.Closing, 10f, 400f, 0f))
        assertEquals(SheetDragEnd.Ignore, sheetDragEnd(OverlayPhase.Opening, 10f, 400f, 0f))
    }

    @Test fun openPhaseDecides() {
        assertEquals(SheetDragEnd.Dismiss, sheetDragEnd(OverlayPhase.Open, 100f, 400f, 0f))
        assertEquals(SheetDragEnd.SnapBack, sheetDragEnd(OverlayPhase.Open, 99f, 400f, 999f))
    }

    @Test fun hostCompletesCloseAfterDismissDuringDrag() {
        val s = OverlayHostState()
        s.showSheet {}
        val e = s.entries.single(); s.onOpened(e.id)
        assertTrue(s.dismiss())
        assertEquals(SheetDragEnd.Ignore, sheetDragEnd(e.phase, 50f, 400f, 0f))
        s.onClosed(e.id)
        assertFalse(s.isAnimating)
        assertTrue(s.showSheet {})
    }
}
