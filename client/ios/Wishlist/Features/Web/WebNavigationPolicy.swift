import Foundation

/// What the web view does with one navigation (spec §4 탐색 규칙).
enum WebDecision: Equatable {
    case loadInside
    case block
    case openExternal
    case confirmExternal
}

/// Pure navigation table, shared vector-for-vector with Android `WebNavigationPolicy`.
///
/// - main frame: `http`/`https` and `about:blank` load inside; `data:`/`blob:` (top-level phishing, Chrome's
///   rule), `javascript:`/`file:`/`content:` and other `about:` pages are blocked.
/// - sub-frame (`targetFrame?.isMainFrame == false`): `http`/`https`/`data`/`blob`/`about` load inside;
///   `javascript`/`file`/`content` are blocked.
/// - any other scheme (`tel`, `mailto`, `itms-apps`, payment apps): an external app, at once on a user gesture
///   (`.linkActivated`), otherwise after the FWebViewExternal confirmation. An empty scheme is blocked.
enum WebNavigationPolicy {
    private static let web: Set<String> = ["http", "https"]
    private static let subresourceOnly: Set<String> = ["data", "blob"]
    private static let neverLoaded: Set<String> = ["javascript", "file", "content"]

    static func decide(scheme: String, mainFrame: Bool, userGesture: Bool, isAboutBlank: Bool) -> WebDecision {
        let s = scheme.lowercased()
        if web.contains(s) { return .loadInside }
        if s == "about" { return !mainFrame || isAboutBlank ? .loadInside : .block }
        if subresourceOnly.contains(s) { return mainFrame ? .block : .loadInside }
        if neverLoaded.contains(s) || s.isEmpty { return .block }
        return userGesture ? .openExternal : .confirmExternal
    }
}
