import XCTest
@testable import Wishlist

final class PriceFormatTests: XCTestCase {
    private func d(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    func testKrwGroupsThousands() {
        XCTAssertEqual(formatPrice(d("549000"), currency: "KRW"), "KRW 549,000")
        XCTAssertEqual(formatPrice(d("1190000"), currency: "KRW"), "KRW 1,190,000")
    }

    func testUsdWholeAmountHasNoFraction() {
        XCTAssertEqual(formatPrice(d("299"), currency: "USD"), "USD 299")
        XCTAssertEqual(formatPrice(d("299.00"), currency: "USD"), "USD 299")
    }

    func testUsdFractionShowsTwoDigits() {
        XCTAssertEqual(formatPrice(d("19.99"), currency: "USD"), "USD 19.99")
        XCTAssertEqual(formatPrice(d("19.5"), currency: "USD"), "USD 19.50")
    }

    func testKrwNeverShowsFraction() {
        XCTAssertEqual(formatPrice(d("999.99"), currency: "KRW"), "KRW 1,000")
    }
}
