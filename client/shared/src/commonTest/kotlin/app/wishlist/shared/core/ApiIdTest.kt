package app.wishlist.shared.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiIdTest {
    @Test
    fun all_37_inventory_entries_have_unique_wire_ids() {
        val expected = setOf(
            "ITEM-01", "ITEM-02", "ITEM-03", "ITEM-04", "ITEM-05", "ITEM-06", "ITEM-07", "ITEM-08",
            "HOME-01", "HOME-02", "DUP-01", "DUP-02",
            "CAT-01", "CAT-02", "CAT-03", "CAT-04", "CAT-05", "CAT-06",
            "PUR-01", "PUR-02", "PUR-03", "PUR-04", "PUR-05", "PUR-06", "PUR-07", "PUR-08",
            "ARC-01", "ARC-02", "ARC-03", "ARC-04", "ARC-05", "ARC-06", "ARC-07", "ARC-08", "ARC-09",
            "MEDIA-01", "MEDIA-02",
        )
        assertEquals(37, ApiId.entries.size)
        assertEquals(expected, ApiId.entries.map { it.wireId }.toSet())
        assertEquals(37, ApiId.entries.map { it.wireId }.distinct().size)
    }

    @Test
    fun kotlin_entry_and_wire_identity_match_for_every_api() {
        for (api in ApiId.entries) assertEquals(api.name.replace('_', '-'), api.wireId)
    }
}
