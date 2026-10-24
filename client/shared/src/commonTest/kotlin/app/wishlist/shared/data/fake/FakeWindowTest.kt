package app.wishlist.shared.data.fake

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.ItemAnchor
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** ITEM-02/HOME-02 window rules (docs/architecture/server/wishlist-item-read-api.md "공용 카드와 page/window"). */
class FakeWindowTest {
    private data class Row(val at: Instant, val id: String)

    /** Newest first, like the scope query: index 0 is the newest. */
    private val rows = (0 until 10).map { Row(fakeTime - it.seconds, "id-$it") }
    private val key: (Row) -> Pair<Instant, String> = { it.at to it.id }

    private fun window(
        sorted: List<Row> = rows, prefix: String = "s:", cursor: String? = null, limit: Int? = null,
        anchor: String? = null, before: Int = 20, after: Int = 20,
    ) = fakeWindow(sorted, key, prefix, cursor, limit, anchor, before, after)

    private fun page(cursor: String? = null, limit: Int = 3, sorted: List<Row> = rows) =
        window(sorted, cursor = cursor, limit = limit).successValue()

    private fun anchorOf(row: Row, prefix: String = "s:") = window(prefix = prefix, limit = 40).successValue().anchorOf(row)

    @Test fun anchorInScopeReturnsBothSides() {
        val slice = window(anchor = anchorOf(rows[4]), before = 2, after = 2).successValue()
        assertEquals(rows.subList(2, 7), slice.items)
        assertEquals(ItemAnchor("id-4", "id-4", true), slice.anchor)
        assertNotNull(slice.previousCursor)
        assertNotNull(slice.nextCursor)
        // Only one side outside the window: the other cursor is null; a short side does not widen the other.
        val top = window(anchor = anchorOf(rows[1]), before = 2, after = 2).successValue()
        assertEquals(rows.subList(0, 4), top.items)
        assertNull(top.previousCursor)
        assertNotNull(top.nextCursor)
    }

    @Test fun removedAnchorFallsToOlderThenNewer() {
        val token = anchorOf(rows[4])
        val withoutFourth = rows - rows[4]
        val older = window(withoutFourth, anchor = token, before = 1, after = 1).successValue()
        assertEquals(ItemAnchor("id-4", "id-5", false), older.anchor)
        assertEquals(listOf(rows[3], rows[5], rows[6]), older.items)
        val last = window(rows.dropLast(1), anchor = anchorOf(rows[9]), before = 1, after = 1).successValue()
        assertEquals(ItemAnchor("id-9", "id-8", false), last.anchor)
        assertEquals(listOf(rows[7], rows[8]), last.items)
        assertNull(last.nextCursor)
    }

    @Test fun emptyScopeKeepsRequestedOnly() {
        val slice = window(emptyList(), anchor = anchorOf(rows[2])).successValue()
        assertEquals(emptyList(), slice.items)
        assertEquals(ItemAnchor("id-2", null, false), slice.anchor)
        assertNull(slice.previousCursor)
        assertNull(slice.nextCursor)
    }

    @Test fun firstPageHasNoPrevious() {
        val first = page()
        assertEquals(rows.subList(0, 3), first.items)
        assertNull(first.previousCursor)
        assertNull(first.anchor)
        val second = page(first.nextCursor)
        assertEquals(rows.subList(3, 6), second.items)
        val back = page(assertNotNull(second.previousCursor))
        assertEquals(rows.subList(0, 3), back.items)
        assertNull(back.previousCursor)
        assertNotNull(back.nextCursor)
        val tail = page(page(second.nextCursor).nextCursor)
        assertEquals(listOf(rows[9]), tail.items)
        assertNull(tail.nextCursor)
    }

    @Test fun emptiedDirectionReturnsInclusiveReturnCursor() {
        val first = page()
        val shrunk = rows.take(3)
        val emptied = page(first.nextCursor, sorted = shrunk)
        assertEquals(emptyList(), emptied.items)
        assertNull(emptied.nextCursor)
        // The return cursor includes the item it points at (the nearest remaining one, rows[2]).
        val back = page(assertNotNull(emptied.previousCursor), limit = 2, sorted = shrunk)
        assertEquals(listOf(rows[1], rows[2]), back.items)
        // Same rule toward newer: everything newer than the boundary is gone.
        val newer = page(page(first.nextCursor).previousCursor, sorted = rows.drop(3))
        assertEquals(emptyList(), newer.items)
        assertNull(newer.previousCursor)
        assertEquals(rows.subList(3, 6), page(assertNotNull(newer.nextCursor), sorted = rows.drop(3)).items)
        // Both directions empty: no cursor at all.
        val none = page(first.nextCursor, sorted = emptyList())
        assertNull(none.previousCursor)
        assertNull(none.nextCursor)
    }

    @Test fun foreignTokenRejected() {
        val next = assertNotNull(page().nextCursor)
        assertEquals(CURSOR, window(prefix = "other:", cursor = next, limit = 3).error().code)
        assertEquals(CURSOR, window(anchor = anchorOf(rows[1], prefix = "other:")).error().code)
        // A page cursor is not an anchor and an anchor is not a page cursor.
        assertEquals(CURSOR, window(anchor = next).error().code)
        assertEquals(CURSOR, window(cursor = anchorOf(rows[1]), limit = 3).error().code)
        assertEquals(CURSOR, window(cursor = "s:n:not-a-time|id-1", limit = 3).error().code)
    }

    @Test fun boundsRejected() {
        val anchor = anchorOf(rows[1])
        assertEquals(QUERY, window(anchor = anchor, before = 21).error().code)
        assertEquals(QUERY, window(anchor = anchor, after = -1).error().code)
        assertEquals(QUERY, window(anchor = anchor, limit = 3).error().code)
        assertEquals(QUERY, window(anchor = anchor, cursor = page().nextCursor).error().code)
        assertEquals(QUERY, window(limit = 0).error().code)
        assertEquals(QUERY, window(limit = 101).error().code)
        assertTrue(window(anchor = anchor, before = 0, after = 0) is ClientResult.Success)
    }

    private companion object {
        const val CURSOR = "INVALID_WISHLIST_ITEM_CURSOR"
        const val QUERY = "INVALID_WISHLIST_ITEM_QUERY"
    }
}
