package app.wishlist.android.designsystem.overlay

import androidx.compose.ui.geometry.Rect
import app.wishlist.android.designsystem.WLButtonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayHostStateTest {
    private val spec = WLDialogSpec("t", emptyList(), "취소", "확인", WLButtonKind.Primary) {}

    private fun OverlayHostState.settleOpen() = entries.filter { it.phase == OverlayPhase.Opening }.forEach { onOpened(it.id) }
    private fun OverlayHostState.settleClose() = entries.filter { it.phase == OverlayPhase.Closing }.forEach { onClosed(it.id) }

    @Test fun showStartsOpeningAndBlocksInput() {
        val s = OverlayHostState()
        assertTrue(s.showSheet {})
        assertTrue(s.isAnimating)
        assertEquals(OverlayPhase.Opening, s.entries.single().phase)
        s.settleOpen()
        assertFalse(s.isAnimating)
    }

    @Test fun dismissDuringOpenIsIgnored() {
        val s = OverlayHostState()
        s.showSheet {}
        assertFalse(s.dismiss())
        assertEquals(OverlayPhase.Opening, s.entries.single().phase)
    }

    @Test fun repeatedDismissClosesOnce() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen()
        assertTrue(s.dismiss())
        assertFalse(s.dismiss())
        assertFalse(s.dismiss())
        s.settleClose()
        assertTrue(s.entries.isEmpty())
        assertFalse(s.isAnimating)
    }

    @Test fun showDuringOpenIsIgnored() {
        val s = OverlayHostState()
        s.showSheet {}
        assertFalse(s.showSheet {})
        assertFalse(s.showDialog(spec))
        assertEquals(1, s.entries.size)
    }

    @Test fun showDuringCloseIsQueuedAndRunsAfterClose() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen(); s.dismiss()
        assertTrue(s.showDialog(spec))
        assertEquals(1, s.entries.size)
        assertEquals(OverlayPhase.Closing, s.entries.single().phase)
        s.settleClose()
        val e = s.entries.single()
        assertTrue(e is DialogEntry)
        assertEquals(OverlayPhase.Opening, e.phase)
    }

    @Test fun laterQueuedShowReplacesEarlier() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen(); s.dismiss()
        s.showSheet {}
        s.showDialog(spec)
        s.settleClose()
        assertTrue(s.entries.single() is DialogEntry)
    }

    @Test fun dismissClosesTopMostOnly() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen()
        s.showDialog(spec); s.settleOpen()
        assertEquals(2, s.entries.size)
        assertTrue(s.dismiss())
        assertEquals(OverlayPhase.Open, s.entries[0].phase)
        assertEquals(OverlayPhase.Closing, s.entries[1].phase)
        s.settleClose()
        assertEquals(1, s.entries.size)
        assertTrue(s.dismiss())
    }

    @Test fun menuOpensAndDismissesLikeOthers() {
        val s = OverlayHostState()
        assertTrue(s.showMenu(Rect(0f, 0f, 10f, 10f), listOf(WLMenuItem("편집") {})))
        s.settleOpen()
        assertTrue(s.dismiss())
        s.settleClose()
        assertTrue(s.entries.isEmpty())
    }
}
