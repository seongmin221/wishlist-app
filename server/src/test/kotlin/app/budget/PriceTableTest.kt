package app.budget

import kotlin.test.*

class PriceTableTest {
    @Test fun `maximum reservation follows the 2500 by 80 cap`() {
        val table = PriceTable()
        assertEquals(596L, table.maximumMicrousd())
        assertEquals(596L, table.costMicrousd(2500, 80))
        assertFailsWith<IllegalArgumentException> { table.costMicrousd(2501, 0) }
        assertFailsWith<IllegalArgumentException> { table.costMicrousd(0, 81) }
    }
}
