package app.wishlist.shared.domain

import app.wishlist.shared.model.DecimalAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceFormatterTest {
    private val formatter = PriceFormatter()

    @Test fun groupsThousandsWithoutLosingPrecision() {
        assertEquals("KRW 549,000", formatter.formatOrNull("549000", "KRW"))
        assertEquals("USD 123,456,789,012,345,678,901,234,567,890.12", formatter.formatOrNull("123456789012345678901234567890.12", "USD"))
    }

    @Test fun wholeAndRoundedWholeOmitFraction() {
        assertEquals("USD 299", formatPrice(DecimalAmount.parseOrNull("299.00"), "USD"))
        assertEquals("USD 20", formatter.formatOrNull("19.995", "USD"))
    }

    @Test fun fractionalAmountsKeepAllMinorUnitDigits() {
        assertEquals("USD 19.90", formatter.formatOrNull("19.9", "USD"))
        assertEquals("KWD 1.200", formatter.formatOrNull("1.2", "KWD"))
    }

    @Test fun roundsHalfUpForZeroThreeAndFourMinorUnits() {
        assertEquals("JPY 20", formatter.formatOrNull("19.5", "JPY"))
        assertEquals("KWD 1.235", formatter.formatOrNull("1.2345", "KWD"))
        assertEquals("CLF 1.2346", formatter.formatOrNull("1.23456", "CLF"))
        assertEquals("USD 1.23", formatter.formatOrNull("1.2349", "USD"))
    }

    @Test fun roundingPropagatesCarryAcrossAllDigits() {
        assertEquals("USD 1,000", formatter.formatOrNull("999.995", "USD"))
        assertEquals("KWD 10", formatter.formatOrNull("9.9995", "KWD"))
        assertEquals("USD 100,000,000,000,000,000,000", formatter.formatOrNull("99999999999999999999.995", "USD"))
    }

    @Test fun negativeHalfUpAndRoundedZeroHaveCorrectSigns() {
        assertEquals("USD -19.90", formatter.formatOrNull("-19.895", "USD"))
        assertEquals("JPY -20", formatter.formatOrNull("-19.5", "JPY"))
        assertEquals("USD 0", formatter.formatOrNull("-0.004", "USD"))
        assertEquals("USD -0.01", formatter.formatOrNull("-0.005", "USD"))
        assertEquals("USD 0", formatter.formatOrNull("-0.000", "USD"))
    }

    @Test fun normalizesCurrencyAndUsesTwoDigitsForUnknownOrUnspecified() {
        assertEquals("JPY 20", formatter.formatOrNull("19.5", " jpy "))
        assertEquals("XYZ 1.25", formatter.formatOrNull("1.25", " xyz "))
        assertEquals("XXX 1.20", formatter.formatOrNull("1.2", "XXX"))
        assertEquals("XAU 1.24", formatter.formatOrNull("1.235", "XAU"))
    }

    @Test fun currentIsoListIncludesUpdatedAndNonDefaultCurrencies() {
        assertEquals("XCG 1.24", formatter.formatOrNull("1.235", "XCG"))
        assertEquals("BHD 1.235", formatter.formatOrNull("1.2345", "BHD"))
        assertEquals("UYW 1.2346", formatter.formatOrNull("1.23456", "UYW"))
        assertEquals("VND 20", formatter.formatOrNull("19.5", "VND"))
    }

    @Test fun missingOrMalformedCurrencyHidesPrice() {
        for (currency in listOf(null, "", "  ", " ??? ", "POINT", "US1", "US", "ＵＳＤ")) {
            assertNull(formatter.formatOrNull("1.2", currency), "currency=$currency")
        }
    }

    @Test fun missingMalformedOrNonfiniteAmountsHidePrice() {
        assertNull(formatPrice(null, "USD"))
        for (amount in listOf(null, "", " ", "NaN", "Infinity", "-Infinity", "1e3", "1,000", ".5", "1.", " 1", "1x")) {
            assertNull(formatter.formatOrNull(amount, "USD"), "amount=$amount")
        }
    }
}
