package app.wishlist.android.designsystem

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class WLHeaderSheetStateTest {
    /** 매 프레임 16ms씩 즉시 진행하는 시계(애니메이션을 끝까지 돌린다). */
    private object InstantClock : MonotonicFrameClock {
        private var t = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            t += 16_000_000L
            return onFrame(t)
        }
    }

    private fun <T> runAnim(block: suspend () -> T): T = runBlocking { withContext(InstantClock) { block() } }

    /** expanded 100, resting 500에 놓인 시트. */
    private fun sheet(detent: WLSheetDetent = WLSheetDetent.Resting) = WLHeaderSheetState(detent).apply {
        maxOvershootPx = 80f
        flingVelocityPx = 1000f
        runAnim { updateAnchors(expanded = 100f, resting = 500f, animate = false) }
    }

    @Test
    fun startsAtSavedDetent() {
        assertEquals(500f, sheet().offset)
        assertEquals(0f, sheet().progress)
        assertEquals(100f, sheet(WLSheetDetent.Expanded).offset)
        assertEquals(1f, sheet(WLSheetDetent.Expanded).progress)
    }

    @Test
    fun dragUpStopsAtExpandedAndMarksIt() {
        val s = sheet()
        assertEquals(-200f, s.dragBy(-200f))
        assertEquals(0.5f, s.progress)
        assertEquals(-200f, s.dragBy(-1000f))
        assertEquals(100f, s.offset)
        assertEquals(WLSheetDetent.Expanded, s.detent)
    }

    @Test
    fun dragBelowRestingRubberBandsAndCaps() {
        val s = sheet()
        s.dragBy(100f)
        assertEquals(500f + 100f * SheetRubberFactor, s.offset)
        s.dragBy(10_000f)
        assertEquals(580f, s.offset)
        assertEquals(0f, s.progress)
    }

    @Test
    fun settleUsesVelocityFirstThenPosition() {
        val s = sheet()
        s.dragBy(-100f) // 400: resting 쪽
        assertEquals(WLSheetDetent.Resting, s.settleTarget(0f))
        assertEquals(WLSheetDetent.Expanded, s.settleTarget(-1500f))
        s.dragBy(-200f) // 200: expanded 쪽
        assertEquals(WLSheetDetent.Expanded, s.settleTarget(0f))
        assertEquals(WLSheetDetent.Resting, s.settleTarget(1500f))
    }

    @Test
    fun headerGrowthMovesRestingSheetDown() {
        val s = sheet()
        runAnim { s.updateAnchors(expanded = 100f, resting = 800f, animate = true) }
        assertEquals(800f, s.offset)
        assertEquals(WLSheetDetent.Resting, s.detent)
    }

    @Test
    fun headerGrowthKeepsExpandedSheet() {
        val s = sheet(WLSheetDetent.Expanded)
        runAnim { s.updateAnchors(expanded = 100f, resting = 800f, animate = true) }
        assertEquals(100f, s.offset)
        // 편집 시작처럼 펼친 상태에서 resting으로 내려오기.
        runAnim { s.animateTo(WLSheetDetent.Resting) }
        assertEquals(800f, s.offset)
        assertEquals(0f, s.progress)
    }

    @Test
    fun listScrollUpMovesSheetFirstThenLeavesRest() {
        val s = sheet()
        val consumed = s.nestedScroll.onPreScroll(Offset(0f, -450f), NestedScrollSource.UserInput)
        assertEquals(-400f, consumed.y) // 시트가 expanded까지 400만 쓰고 남은 50은 목록이 스크롤한다.
        assertEquals(Offset.Zero, s.nestedScroll.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput))
    }

    @Test
    fun listAtTopDragDownMovesSheet() {
        val s = sheet(WLSheetDetent.Expanded)
        val consumed = s.nestedScroll.onPostScroll(Offset.Zero, Offset(0f, 150f), NestedScrollSource.UserInput)
        assertEquals(150f, consumed.y)
        assertEquals(250f, s.offset)
    }
}
