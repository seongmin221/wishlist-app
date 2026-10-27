package app.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductIdentifiersTest {
    @Test fun `valid gtins of every length normalize to fourteen digits`() {
        assertEquals("04006381333931", ProductIdentifiers.gtin(listOf("4006381333931")))
        assertEquals("04006381333931", ProductIdentifiers.gtin(listOf("400-638 1333931")))
        assertEquals("00000096385074", ProductIdentifiers.gtin(listOf("96385074")))
        assertEquals("00036000291452", ProductIdentifiers.gtin(listOf("036000291452")))
        assertEquals("10614141000415", ProductIdentifiers.gtin(listOf("10614141000415")))
    }

    @Test fun `bad check digits zeros other lengths and conflicting values are not identifiers`() {
        assertNull(ProductIdentifiers.gtin(listOf("4006381333932")))
        assertNull(ProductIdentifiers.gtin(listOf("00000000")))
        assertNull(ProductIdentifiers.gtin(listOf("123456789")))
        assertNull(ProductIdentifiers.gtin(listOf("40063813339X1")))
        assertNull(ProductIdentifiers.gtin(emptyList()))
        assertNull(ProductIdentifiers.gtin(listOf("4006381333931", "96385074")))
        assertEquals("04006381333931", ProductIdentifiers.gtin(listOf("4006381333931", "04006381333931")))
        assertNull(ProductIdentifiers.gtin(listOf("4006381333931", "4006381333932")), "one invalid value voids the rest")
    }

    @Test fun `brand and model number form one normalized key`() {
        assertEquals("hoka|1123190BBLC", ProductIdentifiers.mpnKey("HOKA", "1123 190-BBLC"))
        assertEquals("hokaone|ABC1", ProductIdentifiers.mpnKey("Ｈｏｋａ One", "ab.c/1"))
        assertEquals("마샬|MAJORV", ProductIdentifiers.mpnKey(" 마샬 ", "major_v"))
    }

    @Test fun `a key needs both a brand and a model number of usable length`() {
        assertNull(ProductIdentifiers.mpnKey(null, "ABC123"))
        assertNull(ProductIdentifiers.mpnKey("Hoka", null))
        assertNull(ProductIdentifiers.mpnKey("---", "ABC123"))
        assertNull(ProductIdentifiers.mpnKey("Hoka", "AB"))
        assertNull(ProductIdentifiers.mpnKey("Hoka", "A".repeat(65)))
    }
}
