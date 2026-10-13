package app.wishlist.android.feature.web

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D16 one tap, one decision: `hasGesture()` counts once per user action (iOS `testOneTapOpensOneExternalApp`).
 * The events are the ones the holder's touch and key listeners and the accessibility provider pass on.
 */
class WebTapBudgetTest {
    private fun WebTapBudget.tap(x: Float = 100f, y: Float = 100f, upX: Float = x, upY: Float = y) {
        onTouch(MotionEvent.ACTION_DOWN, x, y)
        onTouch(MotionEvent.ACTION_UP, upX, upY)
    }

    private fun tapped() = WebTapBudget(slop = 10f).apply { tap(upX = 104f, upY = 106f) }

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
        b.onTouch(MotionEvent.ACTION_DOWN, 100f, 100f)
        b.onTouch(MotionEvent.ACTION_MOVE, 100f, 140f)
        b.onTouch(MotionEvent.ACTION_UP, 100f, 100f) // a scroll that came back
        assertFalse(b.use(hasGesture = true))
        b.tap(upY = 111f) // lifted past the slop
        assertFalse(b.use(hasGesture = true))
        b.onTouch(MotionEvent.ACTION_DOWN, 100f, 100f)
        b.onTouch(MotionEvent.ACTION_POINTER_DOWN, 100f, 100f)
        b.onTouch(MotionEvent.ACTION_UP, 100f, 100f) // a second finger
        assertFalse(b.use(hasGesture = true))
        b.onTouch(MotionEvent.ACTION_DOWN, 100f, 100f)
        b.onTouch(MotionEvent.ACTION_CANCEL, 100f, 100f)
        b.onTouch(MotionEvent.ACTION_UP, 100f, 100f)
        assertFalse(b.use(hasGesture = true))
        b.tap(50f, 50f)
        assertTrue(b.use(hasGesture = true))
    }

    @Test
    fun keyboardAndDpadPressesAreUserActions() {
        val b = tapped()
        b.use(hasGesture = true)
        b.onKeyDown(KeyEvent.KEYCODE_ENTER)
        assertTrue(b.use(hasGesture = true))
        b.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(b.use(hasGesture = true))
        b.onKeyDown(KeyEvent.KEYCODE_BACK)
        b.onKeyDown(KeyEvent.KEYCODE_ESCAPE)
        assertFalse(b.use(hasGesture = true))
    }

    @Test
    fun anAccessibilityClickIsAUserAction() {
        val b = tapped()
        b.use(hasGesture = true)
        b.onAccessibilityClick() // TalkBack double tap, Switch Access: no touches reach the view
        assertTrue(b.use(hasGesture = true))
        assertFalse(b.use(hasGesture = true))
    }

    @Test
    fun chromiumDecidesWhetherThereIsAGestureAtAll() {
        val b = tapped()
        assertFalse(b.use(hasGesture = false))
        assertFalse(WebTapBudget(slop = 10f).use(hasGesture = false))
    }
}
