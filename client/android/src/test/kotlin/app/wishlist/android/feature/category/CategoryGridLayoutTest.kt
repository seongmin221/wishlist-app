package app.wishlist.android.feature.category

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CategoryGridLayoutTest {
    private suspend fun laidOut(grid: CategoryGridState, vararg ids: String) {
        val layout = grid.layout
        val page = layout.pages.indexOfFirst { it.containsAll(ids.toList()) }
        layout.settlePage(page, ids.map { PhotoPagePlanner.Card(it, hasPhoto = false) }, { emptyMap() }, { 0 })
    }

    private fun urls(vararg ids: String) = ids.associateWith { "https://img/$it" }

    @Test
    fun theFirstItemsAreOnePageAndAnAppendedTailTheNext() {
        val grid = CategoryGridState()
        grid.onItems(listOf("a", "b"), urls("a", "b"))
        grid.onItems(listOf("a", "b", "c"), urls("a", "b", "c"))

        assertEquals(listOf(listOf("a", "b"), listOf("c")), grid.layout.pages.toList())
    }

    /** Back from the detail the grid composes again with the same items: laid-out cards keep their place. */
    @Test
    fun theSameItemsAgainKeepThePagesAndTheirRatios() = runTest {
        val grid = CategoryGridState()
        grid.onItems(listOf("a", "b"), urls("a", "b"))
        laidOut(grid, "a", "b")
        val before = grid.layout

        grid.onItems(listOf("a", "b"), urls("a", "b"))

        assertSame(before, grid.layout)
        assertEquals(listOf(listOf("a", "b")), grid.layout.pages.toList())
        assertEquals(1f, grid.layout.ratios["a"])
    }

    @Test
    fun aRestartedListStartsANewLayout() = runTest {
        val grid = CategoryGridState()
        grid.onItems(listOf("a", "b"), urls("a", "b"))
        laidOut(grid, "a", "b")
        val before = grid.layout

        grid.onItems(listOf("z"), urls("z"))

        assertNotSame(before, grid.layout)
        assertEquals(listOf(listOf("z")), grid.layout.pages.toList())
        assertEquals(emptyMap<String, Float>(), grid.layout.ratios.toMap())
    }

    /** PR A's photo-change restart still holds now that the layout lives in the list entry. */
    @Test
    fun aChangedPhotoStartsANewLayout() = runTest {
        val grid = CategoryGridState()
        grid.onItems(listOf("a", "b"), urls("a", "b"))
        laidOut(grid, "a", "b")
        val before = grid.layout

        grid.onItems(listOf("a", "b"), urls("a", "b") + ("b" to "https://img/b2"))

        assertNotSame(before, grid.layout)
        assertEquals(listOf(listOf("a", "b")), grid.layout.pages.toList())
    }
}
