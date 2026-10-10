import XCTest
@testable import Wishlist

/// Top bar and bottom bar readings of the web view state (spec §4, D6, D13, D14). Same vectors as Android `WebPageStateTest`.
final class WebPageStateTests: XCTestCase {
    private func page(_ url: String = "https://www.musinsa.com/p/1", title: String? = nil, loading: Bool = false, progress: Double = 1) -> WebPageState {
        WebPageState(url: url, title: title, loading: loading, progress: progress)
    }

    func testHostDropsWwwAndUserinfo() {
        XCTAssertEqual(page().host, "musinsa.com")
        XCTAssertEqual(page("https://user:pw@www.shop.example.com:8443/a").host, "shop.example.com")
    }

    func testLockOnlyForHttps() {
        XCTAssertTrue(page("https://a.com").secure)
        XCTAssertTrue(page("HTTPS://a.com").secure)
        XCTAssertFalse(page("http://a.com").secure)
        XCTAssertFalse(page("about:blank").secure)
    }

    func testTitleMissingShowsDomainOnly() {
        XCTAssertNil(page(title: nil).titleLine)
        XCTAssertNil(page(title: "  ").titleLine)
        XCTAssertNil(page("https://a.com/x", title: "https://a.com/x").titleLine)
        XCTAssertNil(page("https://a.com/x", title: "a.com/x").titleLine)
        XCTAssertEqual(page(title: " 상품 페이지 ").titleLine, "상품 페이지")
    }

    func testProgressLineHidesAtTheEnd() {
        XCTAssertTrue(page(loading: true, progress: 0.4).progressVisible)
        XCTAssertFalse(page(loading: true, progress: 1).progressVisible)
        XCTAssertFalse(page(loading: false, progress: 0.4).progressVisible)
    }

    func testReloadBecomesStopWhileLoading() {
        XCTAssertTrue(page(loading: true, progress: 1).showsStop)
        XCTAssertFalse(page(loading: false).showsStop)
    }

    func testShareUsesTheCurrentWebPageElseTheOpenedOne() {
        let opened = URL(string: "https://a.com/start")!
        XCTAssertEqual(page("https://b.com/now").shareURL(fallback: opened), URL(string: "https://b.com/now"))
        XCTAssertEqual(page("about:blank").shareURL(fallback: opened), opened)
        XCTAssertEqual(page("data:text/html,x").shareURL(fallback: opened), opened)
    }

    func testOnlyWebURLsReplaceTheShownPage() {
        var p = page("https://a.com/x")
        p.adopt(currentURL: URL(string: "about:blank"))
        XCTAssertEqual(p.url, "https://a.com/x") // a blocked or blank load keeps the shop's host and lock
        p.adopt(currentURL: nil)
        XCTAssertEqual(p.url, "https://a.com/x")
        p.adopt(currentURL: URL(string: "https://b.com/y"))
        XCTAssertEqual(p.url, "https://b.com/y")
    }
}
