import WebKit
import XCTest
@testable import Wishlist

/// FWebView owner (spec §4): the cookie store settings clears, new windows in place, the external-app flow through
/// `WebNavigationPolicy` + `ExternalPromptGate` (an iframe never loads an external URL), lifetime, and the shell
/// back swipe only without page history.
@MainActor
final class WebViewStoreTests: XCTestCase {
    private let start = URL(string: "https://shop.example.com/p/1")!

    private final class RecordingWebView: WKWebView {
        var loaded: [URLRequest] = []

        override func load(_ request: URLRequest) -> WKNavigation? {
            loaded.append(request)
            return nil
        }
    }

    private final class FakeAction: WKNavigationAction {
        private let fakeRequest: URLRequest
        private let fakeType: WKNavigationType

        init(_ url: URL, type: WKNavigationType = .other) {
            fakeRequest = URLRequest(url: url)
            fakeType = type
            super.init()
        }

        override var request: URLRequest { fakeRequest }
        override var navigationType: WKNavigationType { fakeType }
        override var targetFrame: WKFrameInfo? { nil } // a main-frame (new-window) request
    }

    /// What the screen's FWebViewExternal did with each prompt.
    private final class Prompts {
        var shown: [URL] = []
        var pending: WebViewModel.ExternalPrompt?
        var accept = true
    }

    private func model(_ opened: @escaping (URL) -> Void = { _ in }, prompts: Prompts? = nil) -> WebViewModel {
        let m = WebViewModel(url: start, openExternal: opened)
        if let prompts {
            m.presentPrompt = { prompt in
                guard prompts.accept else { return false }
                prompts.shown.append(prompt.url)
                prompts.pending = prompt
                return true
            }
        }
        return m
    }

    func testWebViewAndCleanerShareTheDefaultStore() {
        let m = model()
        defer { m.close() }
        let store = m.webView.configuration.websiteDataStore
        XCTAssertTrue(store === WishlistWebStore.dataStore)
        XCTAssertTrue(store === WKWebsiteDataStore.default())
        XCTAssertTrue(store.isPersistent)
        XCTAssertTrue(m.webView.allowsBackForwardNavigationGestures)
        XCTAssertTrue(m.webView.navigationDelegate === m)
        XCTAssertTrue(m.webView.uiDelegate === m)
    }

    func testNewWindowLoadsInPlace() {
        let m = model()
        defer { m.close() }
        let view = RecordingWebView(frame: .zero, configuration: WKWebViewConfiguration())
        let target = URL(string: "https://shop.example.com/popup")!
        let made = m.webView(view, createWebViewWith: WKWebViewConfiguration(), for: FakeAction(target), windowFeatures: WKWindowFeatures())
        XCTAssertNil(made)
        XCTAssertEqual(view.loaded.map(\.url), [target])
    }

    func testBlankNewWindowDoesNotWipeThePage() {
        let m = model()
        defer { m.close() }
        let view = RecordingWebView(frame: .zero, configuration: WKWebViewConfiguration())
        let made = m.webView(view, createWebViewWith: WKWebViewConfiguration(), for: FakeAction(URL(string: "about:blank")!), windowFeatures: WKWindowFeatures())
        XCTAssertNil(made)
        XCTAssertTrue(view.loaded.isEmpty)
    }

    func testNewWindowLoadsOnlyWebPageURLs() {
        let m = model()
        defer { m.close() }
        let view = RecordingWebView(frame: .zero, configuration: WKWebViewConfiguration())
        let dropped = ["data:text/html,x", "javascript:alert(1)", "tel:0101234", "file:///etc/hosts", "https:///nohost", "about:srcdoc"]
        for text in dropped {
            guard let url = URL(string: text) else { continue }
            let made = m.webView(view, createWebViewWith: WKWebViewConfiguration(), for: FakeAction(url), windowFeatures: WKWindowFeatures())
            XCTAssertNil(made, text)
        }
        XCTAssertTrue(view.loaded.isEmpty)
        let page = URL(string: "HTTP://m.shop.example.com/2")!
        _ = m.webView(view, createWebViewWith: WKWebViewConfiguration(), for: FakeAction(page), windowFeatures: WKWindowFeatures())
        XCTAssertEqual(view.loaded.map(\.url), [page])
    }

    func testWebAndAboutBlankLoadInsideOthersAreCancelled() {
        let m = model()
        defer { m.close() }
        XCTAssertEqual(m.decide(url: URL(string: "https://a.com"), mainFrame: true, userGesture: false), .allow)
        XCTAssertEqual(m.decide(url: URL(string: "about:blank"), mainFrame: true, userGesture: false), .allow)
        XCTAssertEqual(m.decide(url: URL(string: "data:text/html,x"), mainFrame: true, userGesture: true), .cancel)
        XCTAssertEqual(m.decide(url: URL(string: "data:text/html,x"), mainFrame: false, userGesture: false), .cancel)
        XCTAssertEqual(m.decide(url: URL(string: "javascript:alert(1)"), mainFrame: true, userGesture: true), .cancel)
        XCTAssertEqual(m.decide(url: nil, mainFrame: true, userGesture: true), .cancel)
    }

    func testTappedExternalOpensAtOnce() {
        var opened: [URL] = []
        let prompts = Prompts()
        let m = model({ opened.append($0) }, prompts: prompts)
        defer { m.close() }
        let tel = URL(string: "tel:0101234")!
        XCTAssertEqual(m.decide(url: tel, mainFrame: true, userGesture: true), .cancel)
        XCTAssertEqual(opened, [tel])
        XCTAssertTrue(prompts.shown.isEmpty)
    }

    func testIframeExternalWithoutTapAsksOnceAndNeverLoads() {
        var opened: [URL] = []
        let prompts = Prompts()
        let m = model({ opened.append($0) }, prompts: prompts)
        defer { m.close() }
        let app = URL(string: "itms-apps://apps.apple.com/app/id1")!
        XCTAssertEqual(m.decide(url: app, mainFrame: false, userGesture: false), .cancel)
        XCTAssertEqual(m.decide(url: app, mainFrame: false, userGesture: false), .cancel) // flood: dropped
        XCTAssertEqual(prompts.shown, [app])
        XCTAssertTrue(opened.isEmpty)
        prompts.pending?.open()
        prompts.pending?.closed(true)
        XCTAssertEqual(opened, [app])
    }

    func testCancelSilencesUntilTheMainFrameStartsAPageOnAnotherHost() {
        var opened: [URL] = []
        let prompts = Prompts()
        let m = model({ opened.append($0) }, prompts: prompts)
        defer { m.close() }
        let tel = URL(string: "tel:0101234")!
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        prompts.pending?.closed(false)
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        XCTAssertEqual(prompts.shown.count, 1)
        _ = m.decide(url: tel, mainFrame: true, userGesture: true) // a tap still opens
        XCTAssertEqual(opened, [tel])
        // Ruling 13: keyed on the host, so `?n=2` loops and www./case variants on the same host stay silent.
        m.mainFrameStarted(url: URL(string: "https://shop.example.com/p/1?n=2"))
        m.mainFrameStarted(url: URL(string: "https://WWW.Shop.Example.com/next"))
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        XCTAssertEqual(prompts.shown.count, 1)
        m.mainFrameStarted(url: URL(string: "https://other.example.com/"))
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        XCTAssertEqual(prompts.shown.count, 2)
    }

    func testAPromptTheOverlayRefusedIsNotSilenced() {
        let prompts = Prompts()
        prompts.accept = false
        let m = model(prompts: prompts)
        defer { m.close() }
        let tel = URL(string: "tel:0101234")!
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        prompts.accept = true
        _ = m.decide(url: tel, mainFrame: true, userGesture: false)
        XCTAssertEqual(prompts.shown, [tel])
    }

    func testCloseClearsDelegatesAndIgnoresLaterRequests() {
        var opened: [URL] = []
        let m = model({ opened.append($0) })
        m.close()
        m.close() // idempotent
        XCTAssertTrue(m.isClosed)
        XCTAssertNil(m.webView.navigationDelegate)
        XCTAssertNil(m.webView.uiDelegate)
        XCTAssertEqual(m.decide(url: URL(string: "tel:1")!, mainFrame: true, userGesture: true), .cancel)
        XCTAssertTrue(opened.isEmpty)
        let view = RecordingWebView(frame: .zero, configuration: WKWebViewConfiguration())
        _ = m.webView(view, createWebViewWith: WKWebViewConfiguration(), for: FakeAction(start), windowFeatures: WKWindowFeatures())
        XCTAssertTrue(view.loaded.isEmpty)
    }

    func testEntryOwnersVendOneModelPerEntryAndRetireClosedIds() {
        var made = 0
        let owners = WLEntryOwners(
            makeItem: { preconditionFailure("no item owner here") },
            makeLocal: { preconditionFailure("no local owner here") },
            makeWeb: { url in
                made += 1
                return WebViewModel(url: url)
            }
        )
        let first = owners.web(5, url: start)
        XCTAssertTrue(owners.web(5, url: start) === first)
        XCTAssertEqual(made, 1)
        owners.close([5])
        XCTAssertTrue(first.isClosed)
        let late = owners.web(5, url: start) // a view still drawn during its exit
        XCTAssertTrue(late.isClosed)
        XCTAssertFalse(late === first)
    }

    func testShellBackSwipeOnlyWithoutPageHistory() {
        let nav = WLNavigator()
        let motion = WLNavMotion(navigator: nav)
        motion.reduceMotion = true
        nav.push(AppDestination.web(WebPageURL(start)!).route, sourceKey: "web")
        motion.handle(nav.activeTransition)
        let entry = try! XCTUnwrap(nav.entries(.home).last)
        XCTAssertTrue(motion.canBeginDrag)
        motion.setEdgeBackBlocked(entry.id, true) // the page has history: WKWebView's own swipe goes back
        XCTAssertFalse(motion.canBeginDrag)
        motion.setEdgeBackBlocked(entry.id, false)
        XCTAssertTrue(motion.canBeginDrag)
    }

    // MARK: D16 user tap (Ruling 12)

    func testUserGestureNeedsALinkActivationAndARecentRealTouch() {
        XCTAssertFalse(WebUserGesture.isUserGesture(navigationType: .linkActivated, lastTouch: nil, now: 100))
        XCTAssertTrue(WebUserGesture.isUserGesture(navigationType: .linkActivated, lastTouch: 99.5, now: 100))
        XCTAssertTrue(WebUserGesture.isUserGesture(navigationType: .linkActivated, lastTouch: 99.0, now: 100))
        XCTAssertFalse(WebUserGesture.isUserGesture(navigationType: .linkActivated, lastTouch: 98.5, now: 100))
        XCTAssertFalse(WebUserGesture.isUserGesture(navigationType: .other, lastTouch: 99.9, now: 100))
        XCTAssertFalse(WebUserGesture.isUserGesture(navigationType: .formSubmitted, lastTouch: 99.9, now: 100))
        XCTAssertFalse(WebUserGesture.isUserGesture(navigationType: .linkActivated, lastTouch: 101, now: 100))
    }

    func testScriptedLinkClickAsksAndATouchedOneOpens() {
        var opened: [URL] = []
        let prompts = Prompts()
        let m = model({ opened.append($0) }, prompts: prompts)
        defer { m.close() }
        let tel = URL(string: "tel:0109998888")!
        var policies: [WKNavigationActionPolicy] = []
        // `a.click()` from a timer: .linkActivated with no touch.
        m.webView(m.webView, decidePolicyFor: FakeAction(tel, type: .linkActivated)) { policies.append($0) }
        XCTAssertEqual(prompts.shown, [tel])
        XCTAssertTrue(opened.isEmpty)
        prompts.pending?.closed(true)
        m.noteTouch()
        m.webView(m.webView, decidePolicyFor: FakeAction(tel, type: .linkActivated)) { policies.append($0) }
        XCTAssertEqual(opened, [tel])
        XCTAssertEqual(policies, [.cancel, .cancel])
    }

    func testPagesCannotOpenWindowsWithoutAGesture() {
        let m = model()
        defer { m.close() }
        XCTAssertFalse(WebViewModel.makeConfiguration().preferences.javaScriptCanOpenWindowsAutomatically)
        XCTAssertFalse(m.webView.configuration.preferences.javaScriptCanOpenWindowsAutomatically)
    }

    func testTouchRecognizerNeverCancelsOrBlocksOthers() {
        var touches = 0
        let r = WebTouchStampRecognizer { touches += 1 }
        XCTAssertFalse(r.cancelsTouchesInView)
        XCTAssertFalse(r.delaysTouchesBegan)
        XCTAssertFalse(r.delaysTouchesEnded)
        XCTAssertTrue(r.gestureRecognizer(r, shouldRecognizeSimultaneouslyWith: UIPanGestureRecognizer()))
        XCTAssertEqual(touches, 0)
    }
}
