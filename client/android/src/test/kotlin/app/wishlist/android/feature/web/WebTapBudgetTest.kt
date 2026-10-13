package app.wishlist.android.feature.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D16 one tap, one decision: `hasGesture()` counts once per real tap (iOS `testOneTapOpensOneExternalApp`). */
class WebTapBudgetTest {
    private fun tapped() = WebTapBudget(slop = 10f).apply { onDown(100f, 100f); onUp(104f, 106f) }

    @Test
    fun oneTapIsUsedByTheFirstGestureDecisionOnly() {
        val b = tapped()
        assertTrue(b.use(hasGesture = true)) // tel: opens
        assertFalse(b.use(hasGesture = true)) // the script's kakaotalk:// asks
        assertFalse(b.use(hasGesture = true))
    }

    @Test
    fun aPageLinkUsesTheTapToo() {
        val b = tapped()
        assertTrue(b.use(hasGesture = true)) // an http link loads inside
        assertFalse(b.use(hasGesture = true)) // an external link clicked while it loads asks
    }

    @Test
    fun aNavigationWithoutAGestureLeavesTheTap() {
        val b = tapped()
        assertFalse(b.use(hasGesture = false))
        assertTrue(b.use(hasGesture = true))
    }

    @Test
    fun aNewTapGivesANewGestureButAScrollDoesNot() {
        val b = tapped()
        b.use(hasGesture = true)
        b.onDown(100f, 100f); b.onMove(100f, 140f); b.onUp(100f, 100f) // a scroll that came back
        assertFalse(b.use(hasGesture = true))
        b.onDown(100f, 100f); b.onUp(100f, 111f) // lifted past the slop
        assertFalse(b.use(hasGesture = true))
        b.onDown(100f, 100f); b.onCancel(); b.onUp(100f, 100f) // a second finger or a cancel
        assertFalse(b.use(hasGesture = true))
        b.onDown(50f, 50f); b.onUp(50f, 50f)
        assertTrue(b.use(hasGesture = true))
    }

    @Test
    fun chromiumDecidesWhetherThereIsAGestureAtAll() {
        val b = tapped()
        assertFalse(b.use(hasGesture = false))
        assertFalse(WebTapBudget(slop = 10f).use(hasGesture = false))
    }
}
