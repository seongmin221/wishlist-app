import XCTest
@testable import Wishlist

final class PriceFormatTests: XCTestCase {
    func testCurrencyCodeNormalizationAndUnknownFallback() {
        XCTAssertEqual(formatPrice("19.5", currency: "  jpy "), "JPY 20")
        XCTAssertNil(formatPrice("1.234", currency: " ??? "))
        XCTAssertEqual(formatPrice("1.25", currency: " xyz "), "XYZ 1.25")
        XCTAssertNil(formatPrice("1.2", currency: ""))
    }

    func testIsoCurrencyMinorUnits() {
        XCTAssertEqual(formatPrice("19.5", currency: "JPY"), "JPY 20")
        XCTAssertEqual(formatPrice("1.2345", currency: "KWD"), "KWD 1.235")
        XCTAssertEqual(formatPrice("1.2", currency: "KWD"), "KWD 1.200")
        XCTAssertEqual(formatPrice("1.23456", currency: "CLF"), "CLF 1.2346")
    }

    func testKrwGroupsThousands() {
        XCTAssertEqual(formatPrice("549000", currency: "KRW"), "KRW 549,000")
        XCTAssertEqual(formatPrice("1190000", currency: "KRW"), "KRW 1,190,000")
    }

    func testUsdWholeAmountHasNoFraction() {
        XCTAssertEqual(formatPrice("299", currency: "USD"), "USD 299")
        XCTAssertEqual(formatPrice("299.00", currency: "USD"), "USD 299")
    }

    func testUsdFractionShowsTwoDigits() {
        XCTAssertEqual(formatPrice("19.99", currency: "USD"), "USD 19.99")
        XCTAssertEqual(formatPrice("19.5", currency: "USD"), "USD 19.50")
    }

    func testKrwNeverShowsFraction() {
        XCTAssertEqual(formatPrice("999.99", currency: "KRW"), "KRW 1,000")
    }

    func testHalfUpCarryAndExactLongAmounts() {
        XCTAssertEqual(formatPrice("19.995", currency: "USD"), "USD 20")
        XCTAssertEqual(formatPrice("12345678901234567890123456789012345678901234567890.12", currency: "USD"), "USD 12,345,678,901,234,567,890,123,456,789,012,345,678,901,234,567,890.12")
        XCTAssertEqual(formatPrice("19.9", currency: "USD"), "USD 19.90")
    }

    func testMalformedAndMissingValuesReturnNilWithoutCrashing() {
        for amount: String? in [nil, "", " ", "NaN", "Infinity", "-Infinity", "1e3", "1,000", "no-price"] {
            XCTAssertNil(formatPrice(amount, currency: "USD"))
        }
        for currency: String? in [nil, "", "  ", " ??? "] {
            XCTAssertNil(formatPrice("1.2", currency: currency))
        }
        XCTAssertEqual(formatPrice("1.2", currency: "USD"), "USD 1.20")
    }
}
