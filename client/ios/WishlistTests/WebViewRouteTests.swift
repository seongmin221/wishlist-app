import XCTest
@testable import Wishlist

/// `AppDestination.web` only carries a validated `WebPageURL` (Android `WebViewRouteCodecTest` vectors).
final class WebViewRouteTests: XCTestCase {
    func testWebUrlsAreAcceptedAndKeepTheirParts() throws {
        let plain = try XCTUnwrap(WebPageURL(string: "https://shop.com/a/b?x=1&y=%20#frag"))
        XCTAssertEqual(plain.url.absoluteString, "https://shop.com/a/b?x=1&y=%20#frag")
        XCTAssertEqual(plain.url.query(percentEncoded: true), "x=1&y=%20")
        XCTAssertEqual(plain.url.fragment(percentEncoded: true), "frag")

        let korean = try XCTUnwrap(WebPageURL(string: "https://shop.com/상품/가방?색=검정"))
        XCTAssertEqual(korean.url.host(percentEncoded: false), "shop.com")
        XCTAssertEqual(korean.url.path(percentEncoded: false), "/상품/가방")
        XCTAssertEqual(korean.url.query(percentEncoded: false), "색=검정")

        let emoji = try XCTUnwrap(WebPageURL(string: "http://shop.com/p?q=🎁&r=a/b?c#d=e"))
        XCTAssertEqual(emoji.url.query(percentEncoded: false), "q=🎁&r=a/b?c")
        XCTAssertEqual(emoji.url.fragment(percentEncoded: false), "d=e")

        let upper = try XCTUnwrap(WebPageURL(string: "HTTPS://user@Shop.com:8443/a%2Fb;c=d"))
        XCTAssertEqual(upper.url.port, 8443)
        XCTAssertEqual(WebPageURL(upper.url), upper)
    }

    func testOnlyHttpUrlsWithAHostAreAccepted() {
        let rejected = [
            "javascript:alert(1)",
            "file:///etc",
            "data:text/html,hi",
            "intent://x#Intent;end",
            "https://",
            "https:///path",
            "https://:443/a",
            "https://user@/a",
            "https:shop.com",
            "https:\\\\shop.com",
            "shop.com",
            "",
            "https://shop .com",
            "https://shop.com/a b",
            "https://sh\\op.com/",
        ]
        for string in rejected {
            XCTAssertNil(WebPageURL(string: string), string)
        }
        XCTAssertNil(WebPageURL(URL(string: "javascript:alert(1)")!))
        XCTAssertNil(WebPageURL(URL(fileURLWithPath: "/etc")))
    }

    func testWebDestinationIsAnAccountScopedSlideWithoutTabBar() throws {
        let page = try XCTUnwrap(WebPageURL(string: "https://shop.com/a"))
        let route = AppDestination.web(page).route
        XCTAssertTrue(AppDestination.web(page).accountScoped)
        XCTAssertTrue(route.accountScoped)
        XCTAssertFalse(route.showsTabBar)
        XCTAssertEqual(route.pushStyle, .slide)
        XCTAssertEqual(route.destination, AnyHashable(AppDestination.web(try XCTUnwrap(WebPageURL(string: "https://shop.com/a")))))
        XCTAssertNotEqual(route.destination, AnyHashable(AppDestination.web(try XCTUnwrap(WebPageURL(string: "https://shop.com/b")))))
    }
}
