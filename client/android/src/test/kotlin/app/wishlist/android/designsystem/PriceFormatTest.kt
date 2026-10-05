package app.wishlist.android.designsystem

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceFormatTest {
    @Test fun krwGroupsThousands() {
        assertEquals("KRW 549,000", formatPrice(BigDecimal("549000"), "KRW"))
        assertEquals("KRW 1,190,000", formatPrice(BigDecimal("1190000"), "KRW"))
    }

    @Test fun usdWholeAmountHasNoFraction() {
        assertEquals("USD 299", formatPrice(BigDecimal("299"), "USD"))
        assertEquals("USD 299", formatPrice(BigDecimal("299.00"), "USD"))
    }

    @Test fun usdFractionShowsTwoDigits() {
        assertEquals("USD 19.99", formatPrice(BigDecimal("19.99"), "USD"))
        assertEquals("USD 19.50", formatPrice(BigDecimal("19.5"), "USD"))
    }

    @Test fun krwNeverShowsFraction() {
        assertEquals("KRW 1,000", formatPrice(BigDecimal("999.99"), "KRW"))
    }
}
