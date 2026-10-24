package app.wishlist.android.feature.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C7 list anchor (pure): which card is "first visible" in the 2-column grid and where a restore scrolls. */
class ListAnchorTest {
    // Two columns: a (0..300) c (320..500) | b (0..200) d (220..600).
    private val cards = listOf(
        ListAnchor.CardBox("a", top = 0, bottom = 300),
        ListAnchor.CardBox("b", top = 0, bottom = 200),
        ListAnchor.CardBox("c", top = 320, bottom = 500),
        ListAnchor.CardBox("d", top = 220, bottom = 600),
    )

    @Test fun at_the_top_the_first_card_in_list_order_is_first() {
        assertEquals(ListAnchor.Position("a", 0), ListAnchor.firstVisible(cards, viewportTop = 0))
    }

    @Test fun the_card_crossing_the_top_edge_that_starts_highest_wins() {
        // At 250: a (0..300) and d (220..600) cross the edge; a starts higher.
        assertEquals(ListAnchor.Position("a", 250), ListAnchor.firstVisible(cards, viewportTop = 250))
        // At 310: only d crosses; c starts below the edge.
        assertEquals(ListAnchor.Position("d", 90), ListAnchor.firstVisible(cards, viewportTop = 310))
    }

    @Test fun in_a_gap_the_next_card_below_is_first_with_offset_zero() {
        val gap = listOf(ListAnchor.CardBox("a", 0, 100), ListAnchor.CardBox("b", 140, 200))
        assertEquals(ListAnchor.Position("b", 0), ListAnchor.firstVisible(gap, viewportTop = 120))
        assertNull(ListAnchor.firstVisible(gap, viewportTop = 400))
        assertNull(ListAnchor.firstVisible(emptyList(), viewportTop = 0))
    }

    @Test fun restore_keeps_the_in_card_offset_only_for_the_same_card() {
        val kept = ListAnchor.Position("a", 40)
        assertEquals(540, ListAnchor.scrollTarget(cardTop = 500, kept = kept, resolvedItemId = "a", sameItem = true))
        // The server resolved a fallback: its top.
        assertEquals(500, ListAnchor.scrollTarget(cardTop = 500, kept = kept, resolvedItemId = "b", sameItem = false))
        // Same id but the Presenter says the anchor was not resolved as requested: no offset.
        assertEquals(500, ListAnchor.scrollTarget(cardTop = 500, kept = kept, resolvedItemId = "a", sameItem = false))
        assertEquals(500, ListAnchor.scrollTarget(cardTop = 500, kept = null, resolvedItemId = "a", sameItem = true))
    }

    @Test fun newer_cards_are_asked_for_only_at_the_top_edge() {
        assertTrue(ListAnchor.reachedTop(scrollValue = 0, hasNewer = true, busy = false))
        assertFalse(ListAnchor.reachedTop(scrollValue = 1, hasNewer = true, busy = false))
        assertFalse(ListAnchor.reachedTop(scrollValue = 0, hasNewer = false, busy = false))
        assertFalse(ListAnchor.reachedTop(scrollValue = 0, hasNewer = true, busy = true))
    }
}
