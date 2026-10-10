import XCTest
@testable import Wishlist

/// A page must not flood FWebViewExternal (e.g. `setInterval(() => location = 'tel:…', 200)`). Same vectors as
/// Android `ExternalPromptGateTest`.
final class ExternalPromptGateTests: XCTestCase {
    private let page = "https://shop.com/pay"

    func testOneConfirmationAtATime() {
        let g = ExternalPromptGate()
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
        XCTAssertEqual(g.onRequest(confirm: true), .drop)
        XCTAssertEqual(g.onRequest(confirm: true), .drop)
    }

    func testAfterConfirmTheNextRequestMayAskAgain() {
        let g = ExternalPromptGate()
        _ = g.onRequest(confirm: true)
        g.onPromptClosed(confirmed: true, currentURL: page)
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
    }

    func testCancelSilencesNonGestureRequestsUntilAMainFramePageOnAnotherHost() {
        let g = ExternalPromptGate()
        _ = g.onRequest(confirm: true)
        g.onPromptClosed(confirmed: false, currentURL: page)
        XCTAssertEqual(g.onRequest(confirm: true), .drop)
        g.onMainFrameNavigation(page) // the same page reloading itself does not lift it
        XCTAssertEqual(g.onRequest(confirm: true), .drop)
        // Ruling 13: the silence is keyed on the host, so `?n=2` loops and www./case variants stay silent.
        g.onMainFrameNavigation(page + "?n=2")
        g.onMainFrameNavigation("https://WWW.Shop.com/next")
        XCTAssertEqual(g.onRequest(confirm: true), .drop)
        g.onMainFrameNavigation("https://other.com/")
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
    }

    func testUserTapsAlwaysLaunch() {
        let g = ExternalPromptGate()
        _ = g.onRequest(confirm: true)
        XCTAssertEqual(g.onRequest(confirm: false), .launch) // even while a dialog is up
        g.onPromptClosed(confirmed: false, currentURL: page)
        XCTAssertEqual(g.onRequest(confirm: false), .launch)
    }

    func testClosingWithoutAPromptChangesNothing() {
        let g = ExternalPromptGate()
        g.onPromptClosed(confirmed: false, currentURL: page)
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
    }

    func testAPromptTheOverlayRefusedDoesNotBlockTheNextOne() {
        let g = ExternalPromptGate()
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
        g.onPromptNotShown()
        XCTAssertEqual(g.onRequest(confirm: true), .prompt)
    }
}
