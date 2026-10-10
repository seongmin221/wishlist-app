import Foundation

/// What the web view does with one navigation (spec §4 탐색 규칙).
enum WebDecision: Equatable {
    case loadInside
    case block
    case openExternal
    case confirmExternal
}

/// Pure navigation table, shared vector-for-vector with Android `WebNavigationPolicy`. It only sees frame
/// navigations (main frame or iframe via `targetFrame?.isMainFrame`); images/scripts never reach it.
///
/// - main frame: `http`/`https` and `about:blank` load inside; other `about:` pages, `data:`/`blob:` (top-level
///   phishing, Chrome's rule), `javascript:`/`file:`/`content:` are blocked.
/// - sub-frame (iframe): `http`/`https` and `about:` (`about:blank`, `about:srcdoc`) load inside;
///   `data:`/`blob:` are blocked too (spec §4: allowed for sub-resources only, not iframes), as are
///   `javascript:`/`file:`/`content:`.
/// - a scheme that is not RFC 3986 `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (empty, spaces, tabs) is blocked.
/// - any other scheme (`tel`, `mailto`, `itms-apps`, payment apps): an external app, at once on a user gesture
///   (`.linkActivated`), otherwise after the FWebViewExternal confirmation.
enum WebNavigationPolicy {
    private static let web: Set<String> = ["http", "https"]
    private static let neverLoaded: Set<String> = ["data", "blob", "javascript", "file", "content"]

    static func decide(scheme: String, mainFrame: Bool, userGesture: Bool, isAboutBlank: Bool) -> WebDecision {
        let s = scheme.lowercased()
        guard isValidScheme(s) else { return .block }
        if web.contains(s) { return .loadInside }
        if s == "about" { return !mainFrame || isAboutBlank ? .loadInside : .block }
        if neverLoaded.contains(s) { return .block }
        return userGesture ? .openExternal : .confirmExternal
    }

    /// `about:blank` with an optional `?query`/`#fragment` (`about:blank#x` → true, `about:srcdoc` → false).
    static func isAboutBlank(_ url: String) -> Bool {
        let prefix = "about:"
        guard url.lowercased().hasPrefix(prefix) else { return false }
        let rest = url.dropFirst(prefix.count).prefix { $0 != "?" && $0 != "#" }
        return rest.lowercased() == "blank"
    }

    /// Lowercase ASCII `[a-z][a-z0-9+.-]*`.
    private static func isValidScheme(_ s: String) -> Bool {
        guard let first = s.unicodeScalars.first, ("a"..."z").contains(first) else { return false }
        return s.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || "+.-".unicodeScalars.contains($0) }
    }
}
