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

    @Test fun confirmThenDismissAllClosesDialogAndSheetBelow() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen()
        var confirmed = 0
        s.showDialog(spec.copy(onConfirm = { confirmed++; assertTrue(s.dismissAll()) })); s.settleOpen()
        val dialog = s.entries.last()
        assertTrue(s.confirm(dialog.id))
        assertEquals(1, confirmed)
        assertFalse(s.confirm(dialog.id)) // 두 번째 확인은 무시
        assertEquals(OverlayPhase.Open, s.entries[0].phase)
        assertEquals(OverlayPhase.Closing, s.entries[1].phase)
        s.settleClose() // 확인창 제거 -> 시트 닫기 시작
        val sheet = s.entries.single()
        assertTrue(sheet is SheetEntry)
        assertEquals(OverlayPhase.Closing, sheet.phase)
        s.settleClose()
        assertTrue(s.entries.isEmpty())
        assertFalse(s.isAnimating)
        // 끝난 뒤에는 평소처럼 하나씩 닫는다.
        s.showSheet {}; s.settleOpen(); s.showDialog(spec); s.settleOpen()
        assertTrue(s.dismiss()); s.settleClose()
        assertEquals(OverlayPhase.Open, s.entries.single().phase)
    }

    @Test fun dismissInsideOnConfirmIsStillIgnored() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen()
        s.showDialog(spec.copy(onConfirm = { assertFalse(s.dismiss()) })); s.settleOpen()
        assertTrue(s.confirm(s.entries.last().id))
        s.settleClose()
        assertEquals(OverlayPhase.Open, s.entries.single().phase)
    }

    @Test fun dismissAllFromOpenStackClosesTopFirst() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen(); s.showDialog(spec); s.settleOpen()
        assertTrue(s.dismissAll())
        assertEquals(listOf(OverlayPhase.Open, OverlayPhase.Closing), s.entries.map { it.phase })
        s.settleClose(); s.settleClose()
        assertTrue(s.entries.isEmpty())
    }

    @Test fun dismissAllIgnoredWhileOpeningOrEmpty() {
        val s = OverlayHostState()
        assertFalse(s.dismissAll())
        s.showSheet {}
        assertFalse(s.dismissAll())
        assertEquals(OverlayPhase.Opening, s.entries.single().phase)
    }

    @Test fun dismissAllDropsEarlierQueuedShow() {
        val s = OverlayHostState()
        s.showSheet {}; s.settleOpen(); s.showMenu(Rect.Zero, emptyList()); s.settleOpen()
        s.dismiss()
        s.showDialog(spec) // 줄 세움
        assertTrue(s.dismissAll())
        s.settleClose(); s.settleClose()
        assertTrue(s.entries.isEmpty())
    }

    @Test fun isShowingCoversClosing() {
        val s = OverlayHostState()
        assertFalse(s.isShowing)
        s.showSheet {}; assertTrue(s.isShowing)
        s.settleOpen(); s.dismiss(); assertTrue(s.isShowing)
        s.settleClose(); assertFalse(s.isShowing)
    }

    @Test fun dialogReportsItsCloseOnceWhetherCancelledOrConfirmed() {
        var closed = 0
        var confirmed = 0
        val tracked = WLDialogSpec("t", emptyList(), "취소", "확인", WLButtonKind.Primary, onDismissed = { closed++ }) { confirmed++ }
        val s = OverlayHostState()
        s.showDialog(tracked); s.settleOpen()
        s.dismiss(); s.settleClose()
        assertEquals(1, closed)
        assertEquals(0, confirmed)
        s.showDialog(tracked); s.settleOpen()
        s.confirm(s.entries.single().id); s.settleClose()
        assertEquals(2, closed)
        assertEquals(1, confirmed)
    }
}
