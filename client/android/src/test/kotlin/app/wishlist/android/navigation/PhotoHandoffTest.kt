package app.wishlist.android.navigation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PhotoHandoffTest {
    private val handoff = PhotoHandoff<String, String>()

    @Test
    fun heldCardPhotoAndItsCacheKeyReachTheDetail() {
        handoff.hold(7, "card", memoryCacheKey = "url#w320")

        assertEquals(PhotoHandoff.Held("card", "url#w320"), handoff.forward(7))
        assertNull(handoff.forward(8))
    }

    @Test
    fun forwardIsReleasedOnlyAfterTheDetailPhotoIsDrawnAndTheMotionEnded() {
        handoff.hold(7, "card", null)

        handoff.detailDrawn(7)
        assertEquals("card", handoff.forward(7)?.image) // still flying
        handoff.motionEnded(7)
        assertNull(handoff.forward(7))
    }

    @Test
    fun motionEndingFirstWaitsForTheDetailPhoto() {
        handoff.hold(7, "card", null)

        handoff.motionEnded(7)
        assertEquals("card", handoff.forward(7)?.image) // detail photo not drawn yet: keep showing the card photo
        handoff.detailDrawn(7)
        assertNull(handoff.forward(7))
    }

    @Test
    fun removedEntriesReleaseBothDirections() {
        handoff.hold(7, "card", null)
        handoff.beginReverse(7, "category/c/i", "detail")

        handoff.drop(listOf(7L))

        assertNull(handoff.forward(7))
        assertNull(handoff.reverseFor("category/c/i"))
    }

    @Test
    fun reverseShowsTheDetailPhotoOnTheCardUntilTheBackEnds() {
        handoff.beginReverse(7, "category/c/i", "detail")

        assertEquals("detail", handoff.reverseFor("category/c/i"))
        assertNull(handoff.reverseFor("category/c/other"))
        handoff.endReverse(7)
        assertNull(handoff.reverseFor("category/c/i"))
    }

    @Test
    fun reverseFollowsTheDetailPhotoChangingDuringTheBack() {
        handoff.beginReverse(7, "category/c/i", "card-size")
        handoff.beginReverse(7, "category/c/i", "detail-size")

        assertEquals("detail-size", handoff.reverseFor("category/c/i"))
    }

    @Test
    fun aDetailIsLeavingDuringItsPopOrBackGestureOnly() {
        val tab = WLTab.Category
        // Back gesture: the stack has not changed yet, the detail is still the top.
        assertTrue(PhotoHandoff.isLeaving(WLNavTransition.BackGesture(tab), tab, entryId = 3, stack = listOf(0, 3)))
        // Pop: the detail already left the stack and is drawn as the exiting entry.
        assertTrue(PhotoHandoff.isLeaving(WLNavTransition.Pop(tab), tab, entryId = 3, stack = listOf(0)))
        // A pop of the screen above this detail is not this detail leaving.
        assertFalse(PhotoHandoff.isLeaving(WLNavTransition.Pop(tab), tab, entryId = 3, stack = listOf(0, 3)))
        assertFalse(PhotoHandoff.isLeaving(WLNavTransition.BackGesture(tab), tab, entryId = 3, stack = listOf(0, 3, 4)))
        assertFalse(PhotoHandoff.isLeaving(WLNavTransition.Push(tab), tab, entryId = 3, stack = listOf(0, 3)))
        assertFalse(PhotoHandoff.isLeaving(WLNavTransition.Pop(WLTab.Home), tab, entryId = 3, stack = listOf(0)))
        assertFalse(PhotoHandoff.isLeaving(null, tab, entryId = 3, stack = listOf(0, 3)))
    }
}
