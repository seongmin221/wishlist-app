package app.wishlist.android.feature.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryGridLayoutTest {
    private fun laidOut(layout: CategoryGridLayout, vararg ids: String) {
        val page = layout.pages.indexOfFirst { it.containsAll(ids.toList()) }
        layout.settle(page, ids.map { PhotoPagePlanner.Card(it, hasPhoto = false) }, elapsedMs = 0)
    }

    @Test
    fun theFirstItemsAreOnePageAndAnAppendedTailTheNext() {
        val layout = CategoryGridLayout()
        layout.onItems(listOf("a", "b"))
        layout.onItems(listOf("a", "b", "c"))

        assertEquals(listOf(listOf("a", "b"), listOf("c")), layout.pages.toList())
    }

    /** Back from the detail the grid composes again with the same items: laid-out cards keep their place. */
    @Test
    fun theSameItemsAgainKeepThePagesAndTheirRatios() {
        val layout = CategoryGridLayout()
        layout.onItems(listOf("a", "b"))
        laidOut(layout, "a", "b")

        layout.onItems(listOf("a", "b"))

        assertEquals(listOf(listOf("a", "b")), layout.pages.toList())
        assertEquals(1f, layout.ratios["a"])
        assertEquals(1, layout.laidOutPages)
    }

    @Test
    fun aRestartedListForgetsPagesRatiosAndSizes() {
        val layout = CategoryGridLayout()
        layout.onItems(listOf("a", "b"))
        laidOut(layout, "a", "b")
        layout.photos["a"] = PhotoPagePlanner.Photo.Failed

        layout.onItems(listOf("z"))

        assertEquals(listOf(listOf("z")), layout.pages.toList())
        assertTrue(layout.ratios.isEmpty())
        assertTrue(layout.photos.isEmpty())
        assertEquals(0, layout.laidOutPages)
    }
}
