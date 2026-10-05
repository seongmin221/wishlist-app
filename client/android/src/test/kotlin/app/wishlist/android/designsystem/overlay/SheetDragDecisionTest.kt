package app.wishlist.android.designsystem.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetDragDecisionTest {
    @Test fun dismissesAtQuarterOfHeight() = assertTrue(shouldDismissSheet(100f, 400f, 0f))
    @Test fun keepsBelowThresholds() = assertFalse(shouldDismissSheet(99f, 400f, 999f))
    @Test fun dismissesOnFastFling() = assertTrue(shouldDismissSheet(10f, 400f, 1000f))
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
