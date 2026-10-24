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

    @Test fun newer_cards_are_asked_for_once_per_visit_to_the_top_edge() {
        val top = ListAnchor.TopEdgeTrigger()
        // A user scroll reaching the top with newer cards: once.
        assertTrue(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = false))
        // In flight, then the read failed (prepending false, hasNewer still true, still at the top): not again (Ruling 9).
        assertFalse(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = true))
        assertFalse(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = false))
        // Leaving the top re-arms; coming back asks again.
        assertFalse(top.update(scrollValue = 40, userAtEdge = true, hasNewer = true, busy = false))
        assertTrue(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = false))
    }

    @Test fun the_top_edge_does_not_ask_without_newer_cards_busy_or_a_programmatic_scroll() {
        val top = ListAnchor.TopEdgeTrigger()
        assertFalse(top.update(scrollValue = 0, userAtEdge = true, hasNewer = false, busy = false))
        assertFalse(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = true))
        // A restore or a shrinking layout putting the scroll at 0 is not the user reaching the top.
        assertFalse(top.update(scrollValue = 0, userAtEdge = false, hasNewer = true, busy = false))
        assertTrue(top.update(scrollValue = 0, userAtEdge = true, hasNewer = true, busy = false))
    }

    @Test fun a_restore_that_resolved_nothing_goes_to_the_top_only_where_asked() {
        // Category list: the top. Purpose detail (Ruling 8): stays where it is (null = no scroll).
        assertEquals(0, ListAnchor.nullRestoreTarget(keepPosition = false))
        assertNull(ListAnchor.nullRestoreTarget(keepPosition = true))
    }
}
