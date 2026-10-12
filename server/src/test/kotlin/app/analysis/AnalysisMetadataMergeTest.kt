package app.analysis

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisMetadataMergeTest {
    private val existing = StoredMetadata("Old", "old description", "https://example.com/old.jpg", "https://example.com/old",
        "Old brand", "Old shop", BigDecimal("1000"), "KRW", "AI", "AI", emptySet())
    private val page = PendingMetadata("New", "new description", "https://example.com/new.jpg", "https://example.com/new",
        null, "New shop", null, null)

    @Test fun `success keeps existing optional values when the new page has none`() {
        val merged = mergeMetadata(existing, page, complete = true)
        assertEquals("New", merged.name)
        assertEquals("Old brand", merged.brand)
        assertEquals("New shop", merged.merchant)
    }

    @Test fun `user brand override survives a successful analysis`() {
        val merged = mergeMetadata(existing.copy(overrides = setOf("BRAND")), page.copy(brand = "AI brand"), complete = true)
        assertEquals("Old brand", merged.brand)
        assertEquals("AI brand", mergeMetadata(existing, page.copy(brand = "AI brand"), complete = true).brand)
    }

    @Test fun `failure only fills empty values`() {
        val merged = mergeMetadata(existing.copy(brand = null, name = null), page.copy(brand = "B"), complete = false)
        assertEquals("B", merged.brand)
        assertEquals("New", merged.name)
        assertEquals("Old shop", merged.merchant)
    }

    @Test fun `a page read replaces the price pair including clearing it and records the check time`() {
        val cleared = mergeMetadata(existing, page, complete = true)
        assertNull(cleared.price); assertNull(cleared.currency); assertTrue(cleared.recordCheckedAt)
        val failed = mergeMetadata(existing, page.copy(price = BigDecimal("20.5"), currency = "USD"), complete = false)
        assertEquals(BigDecimal("20.5"), failed.price); assertEquals("USD", failed.currency); assertTrue(failed.recordCheckedAt)
    }

    @Test fun `without page metadata the price pair and check time stay`() {
        val merged = mergeMetadata(existing, PendingMetadata(null, null, null, null, "X", null, BigDecimal("1"), "USD"), complete = true)
        assertEquals(BigDecimal("1000"), merged.price); assertEquals("KRW", merged.currency); assertFalse(merged.recordCheckedAt)
    }

    @Test fun `user name and image sources stay protected`() {
        val merged = mergeMetadata(existing.copy(nameSource = "USER", overrides = setOf("IMAGE")), page, complete = true)
        assertEquals("Old", merged.name); assertEquals("USER", merged.nameSource)
        assertEquals("https://example.com/old.jpg", merged.image); assertEquals("AI", merged.imageSource)
    }

    @Test fun `new AI values set AI source only when applied`() {
        val merged = mergeMetadata(existing.copy(name = null, nameSource = null), page, complete = false)
        assertEquals("AI", merged.nameSource)
        assertNull(mergeMetadata(existing.copy(name = null, nameSource = null), page.copy(name = null), complete = true).nameSource)
    }
}
