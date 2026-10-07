package app.wishlist.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceFormatTest {
    @Test fun normalizedAndUnknownCurrencyNeverCrashRendering() {
        assertEquals("JPY 1,000", formatPrice("999.99", " jpy "))
        assertNull(formatPrice("1.25", " point "))
        assertEquals("XYZ 1.25", formatPrice("1.25", " xyz "))
        assertEquals("XXX 1.25", formatPrice("1.25", "XXX"))
    }

    @Test fun isoMinorUnitsIncludeZeroAndThreeDigits() {
        assertEquals("JPY 1,000", formatPrice("999.99", "JPY"))
        assertEquals("KWD 1.235", formatPrice("1.2346", "KWD"))
    }

    @Test fun krwGroupsThousands() {
        assertEquals("KRW 549,000", formatPrice("549000", "KRW"))
        assertEquals("KRW 1,190,000", formatPrice("1190000", "KRW"))
    }

    @Test fun usdWholeAmountHasNoFraction() {
        assertEquals("USD 299", formatPrice("299", "USD"))
        assertEquals("USD 299", formatPrice("299.00", "USD"))
    }

    @Test fun usdFractionShowsTwoDigits() {
        assertEquals("USD 19.99", formatPrice("19.99", "USD"))
        assertEquals("USD 19.50", formatPrice("19.5", "USD"))
    }

    @Test fun krwNeverShowsFraction() {
        assertEquals("KRW 1,000", formatPrice("999.99", "KRW"))
    }
    @Test fun fourMinorUnitsAndHalfUpCarry() {
        assertEquals("CLF 1.2346", formatPrice("1.23456", "CLF"))
        assertEquals("USD 20", formatPrice("19.995", "USD"))
    }

    @Test fun malformedAndMissingValuesHidePrice() {
        for (amount in listOf(null, "", " ", "NaN", "Infinity", "1e3", "1,000", "no-price")) {
            assertNull(formatPrice(amount, "USD"))
        }
        for (currency in listOf(null, "", "  ", " ??? ")) {
            assertNull(formatPrice("1.2", currency))
        }
        assertEquals("USD 19.90", formatPrice("19.9", "USD"))
    }

    @Test fun longAmountsKeepExactStringPrecision() {
        assertEquals("USD 123,456,789,012,345,678,901,234,567,890.12", formatPrice("123456789012345678901234567890.12", "USD"))
    }
}
