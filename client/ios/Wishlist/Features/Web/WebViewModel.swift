import Observation
import UIKit
import WebKit

/// Lifetime owner of one FWebView route's `WKWebView` (spec §4 수명; Android `WebViewHolder`). Kept per back-stack
/// entry by `WLEntryOwners.web(_:url:)`, so the page and its history live as long as the entry, including trips to
/// other apps (D15). `close()` (pop, account change) stops loading and clears the delegates; `deinit` is the backup.
/// After process death the route opens its original URL only (D15).
///
/// It is the web view's navigation and UI delegate:
/// - `decidePolicyFor` → `decide(url:mainFrame:userGesture:)`: `WebNavigationPolicy` with
///   `targetFrame?.isMainFrame ?? true` (nil = a new-window request) and `.linkActivated` as the user gesture.
///   External apps open with `UIApplication.open` (never `canOpenURL`; a false result does nothing, D16); without
///   a gesture they go through `ExternalPromptGate` and FWebViewExternal (`presentPrompt`). Every external request,
///   from the main frame or an iframe, is cancelled in WebKit, so it never loads into any frame.
/// - `createWebViewWith` loads the request in this web view and returns nil (`target=_blank`, `window.open`). The
///   load is asked about again as a main-frame navigation. A blank new window (`window.open()`) is not loaded: it
///   would wipe the page.
/// - Bars: KVO of `url`, `title`, `isLoading`, `estimatedProgress`, `canGoBack`, `canGoForward` into `page`.
///   Main-frame `didFailProvisionalNavigation`/`didFail` (not a cancel) or a web content crash set `failed`.
@MainActor
@Observable
final class WebViewModel: NSObject, WKNavigationDelegate, WKUIDelegate {
    /// One FWebViewExternal question. The screen calls `open()` from 열기 and `closed(confirmed)` once the dialog
    /// is gone (취소, 열기 or escape).
    struct ExternalPrompt {
        let url: URL
        let open: () -> Void
        let closed: (_ confirmed: Bool) -> Void
    }

    let initialURL: URL
    private(set) var page: WebPageState
    private(set) var isClosed = false

    @ObservationIgnored let webView: WKWebView
    /// The screen's FWebViewExternal; returns false when the dialog could not be shown. Nil = not shown.
    @ObservationIgnored var presentPrompt: ((ExternalPrompt) -> Bool)?

    @ObservationIgnored private let gate = ExternalPromptGate()
    @ObservationIgnored private let openExternal: (URL) -> Void
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var started = false
    /// The main-frame URL that failed (retry loads it; the web view may still show the previous page).
    @ObservationIgnored private var failedURL: URL?

    init(url: URL, openExternal: @escaping (URL) -> Void = WebViewModel.openWithSystem) {
        initialURL = url
        page = WebPageState(url: url.absoluteString)
        self.openExternal = openExternal
        webView = WKWebView(frame: .zero, configuration: Self.makeConfiguration())
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        observe()
    }

    /// WebKit defaults otherwise: JavaScript on, `javaScriptCanOpenWindowsAutomatically` false (a page cannot open
    /// a window, and so cannot replace this page through `createWebViewWith`, without a user gesture).
    static func makeConfiguration() -> WKWebViewConfiguration {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WishlistWebStore.dataStore
        configuration.allowsInlineMediaPlayback = true
        return configuration
    }

    static func openWithSystem(_ url: URL) {
        // D16: no `canOpenURL` (it needs LSApplicationQueriesSchemes); a false result does nothing.
        UIApplication.shared.open(url, options: [:]) { _ in }
    }

    // MARK: Screen intents

    /// The screen's first appearance opens the route URL; a recreated view does not reload it.
    func start() {
        guard !started, !isClosed else { return }
        started = true
        webView.load(URLRequest(url: initialURL))
    }

    func goBack() {
        if webView.canGoBack { webView.goBack() }
    }

    func goForward() {
        if webView.canGoForward { webView.goForward() }
    }

    func reload() {
        guard !isClosed else { return }
        if webView.url == nil { webView.load(URLRequest(url: initialURL)) } else { webView.reload() }
    }

    func stop() {
        webView.stopLoading()
        page.loading = false
    }

    /// "다시 시도": load the page that failed (or reload the one shown).
    func retry() {
        guard !isClosed else { return }
        page.failed = false
        if let failedURL {
            self.failedURL = nil
            webView.load(URLRequest(url: failedURL))
        } else {
            reload()
        }
    }

    /// Idempotent. A screen still drawn during its exit keeps the view; it no longer reports or navigates.
    func close() {
        guard !isClosed else { return }
        isClosed = true
        presentPrompt = nil
        observations.forEach { $0.invalidate() }
        observations = []
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
    }

    deinit {
        // Backup for an owner dropped without close(): the delegates are weak; stop the page's network and scripts.
        let view = webView
        if Thread.isMainThread {
            MainActor.assumeIsolated { view.stopLoading() }
        } else {
            DispatchQueue.main.async { view.stopLoading() }
        }
    }

    // MARK: Navigation

    /// The policy of one frame navigation (main frame or iframe).
    func decide(url: URL?, mainFrame: Bool, userGesture: Bool) -> WKNavigationActionPolicy {
        guard !isClosed, let url else { return .cancel }
        let decision = WebNavigationPolicy.decide(
            scheme: url.scheme ?? "",
            mainFrame: mainFrame,
            userGesture: userGesture,
            isAboutBlank: WebNavigationPolicy.isAboutBlank(url.absoluteString)
        )
        switch decision {
        case .loadInside: return .allow
        case .block: return .cancel
        case .openExternal:
            requestExternal(url, confirm: false)
            return .cancel
        case .confirmExternal:
            requestExternal(url, confirm: true)
            return .cancel
        }
    }

    /// The main frame started loading `url` (lifts the gate's silence for a different page).
    func mainFrameStarted(url: URL?) {
        if let url { gate.onMainFrameNavigation(url.absoluteString) }
        page.failed = false
        failedURL = nil
    }

    private func requestExternal(_ url: URL, confirm: Bool) {
        switch gate.onRequest(confirm: confirm) {
        case .launch:
            openExternal(url)
        case .drop:
            break
        case .prompt:
            let prompt = ExternalPrompt(
                url: url,
                open: { [weak self] in
                    guard let self, !self.isClosed else { return }
                    self.openExternal(url)
                },
                closed: { [weak self] confirmed in
                    guard let self else { return }
                    self.gate.onPromptClosed(confirmed: confirmed, currentURL: self.page.url)
                }
            )
            if presentPrompt?(prompt) != true { gate.onPromptNotShown() }
        }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        decisionHandler(decide(
            url: navigationAction.request.url,
            mainFrame: navigationAction.targetFrame?.isMainFrame ?? true,
            userGesture: navigationAction.navigationType == .linkActivated
        ))
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        mainFrameStarted(url: webView.url)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        mainFrameFailed(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        mainFrameFailed(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // The page's process died: offer "다시 시도" (Android onRenderProcessGone).
        failedURL = webView.url ?? URL(string: page.url)
        page.loading = false
        page.failed = true
    }

    /// D13: only the main frame's own errors (WebKit reports no iframe errors here). A cancel (a newer navigation,
    /// stop) and WebKit's "frame load interrupted" (a response the web view does not show) are not failures.
    private func mainFrameFailed(_ error: Error) {
        let e = error as NSError
        if e.domain == NSURLErrorDomain, e.code == NSURLErrorCancelled { return }
        if e.domain == "WebKitErrorDomain", e.code == 102 { return }
        failedURL = (e.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? webView.url
        page.failed = true
    }

    // MARK: WKUIDelegate

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard !isClosed, let url = navigationAction.request.url, !url.absoluteString.isEmpty,
              !WebNavigationPolicy.isAboutBlank(url.absoluteString)
        else { return nil }
        webView.load(navigationAction.request)
        return nil
    }

    // MARK: Bars

    private func observe() {
        func watch<V>(_ key: KeyPath<WKWebView, V>) -> NSKeyValueObservation {
            webView.observe(key, options: [.new]) { [weak self] _, _ in
                MainActor.assumeIsolated { self?.sync() }
            }
        }
        observations = [
            watch(\.url), watch(\.title), watch(\.isLoading), watch(\.estimatedProgress),
            watch(\.canGoBack), watch(\.canGoForward),
        ]
        sync()
    }

    private func sync() {
        guard !isClosed else { return }
        var next = page
        next.adopt(currentURL: webView.url)
        next.title = webView.title
        next.loading = webView.isLoading
        next.progress = webView.estimatedProgress
        next.canGoBack = webView.canGoBack
        next.canGoForward = webView.canGoForward
        if next != page { page = next }
    }
}
