package app.wishlist.shared.presentation

import app.wishlist.shared.model.ItemAnchor
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.itemFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemWindowTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun items(range: IntRange) = range.map { itemFixture(id = id(it), name = "상품 $it") }
    private fun anchors(range: IntRange) = range.associate { id(it) to "a$it" }

    @Test fun replaceAroundKeepsCursors() {
        val first = ItemWindow.Empty.replace(ItemPage(items(1..40), 80, "n40", null, anchors(1..40)))
        val window = first.replace(ItemPage(items(10..50), 80, "n50", "p10", anchors(10..50), ItemAnchor(id(30), id(30), true)))
        assertEquals(items(10..50), window.items)
        assertEquals(80, window.totalCount)
        assertEquals("n50", window.nextCursor)
        assertEquals("p10", window.previousCursor)
        assertEquals("a30", window.anchorOf(id(30)))
        assertNull(window.anchorOf(id(1))) // the earlier window is dropped with its anchors
    }

    @Test fun prependDedupes() {
        val window = ItemWindow.Empty.replace(ItemPage(items(10..20), 50, "n20", "p10", anchors(10..20)))
            .prepend(ItemPage(items(5..11), 51, "n11", "p5", anchors(5..11)))
        assertEquals(items(5..9) + items(10..20), window.items)
        assertEquals(51, window.totalCount)
        assertEquals("p5", window.previousCursor)
        assertEquals("n20", window.nextCursor) // the newer page's own next cursor points back into the window
        assertEquals("a5", window.anchorOf(id(5)))
        assertEquals("a20", window.anchorOf(id(20)))
    }

    @Test fun appendDedupes() {
        val window = ItemWindow.Empty.replace(ItemPage(items(10..20), 50, "n20", "p10", anchors(10..20)))
            .append(ItemPage(items(19..30), 50, null, "p19", anchors(19..30)))
        assertEquals(items(10..30), window.items)
        assertEquals("p10", window.previousCursor)
        assertNull(window.nextCursor)
        assertEquals("a30", window.anchorOf(id(30)))
        assertEquals("a10", window.anchorOf(id(10)))
    }
}
