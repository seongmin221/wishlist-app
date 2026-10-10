import Foundation
import Shared

/// What the FWebView bars show for the current page (spec §4, D13, D14; Android `WebPageState`). `url` is the
/// page's current URL (the share sheet uses it, D6), `progress` WKWebView's `estimatedProgress` (0–1), `loading`
/// its `isLoading`, `failed` a main-frame load error (sub-resource errors never set it).
struct WebPageState: Equatable {
    var url: String
    var title: String?
    var loading = false
    var progress: Double = 0
    var canGoBack = false
    var canGoForward = false
    var failed = false

    /// `www.`-less host without userinfo or port, like home (D14).
    var host: String { DisplayFormat.shared.host(url: url) }

    /// Lock icon only for https (D14).
    var secure: Bool { url.lowercased().hasPrefix("https:") }

    /// Second line; nil (domain only, D13) when the page has no title or reports its URL as one.
    var titleLine: String? {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t == url { return nil }
        if let range = url.range(of: "://"), t == String(url[range.upperBound...]) { return nil }
        return t
    }

    /// The 2px line hides once the load reaches the end (D13).
    var progressVisible: Bool { loading && progress < 1 }

    /// Reload turns into stop while loading (D13).
    var showsStop: Bool { loading }

    /// The web view's current URL, when it is a web page. `about:blank` and the like keep the last web page, so the
    /// bar never shows "about:blank" as a domain.
    mutating func adopt(currentURL: URL?) {
        if let currentURL, WebPageURL(currentURL) != nil { url = currentURL.absoluteString }
    }

    /// FWebViewShare's URL: the page now shown when it is a web page, else the one the screen opened (D6).
    func shareURL(fallback: URL) -> URL {
        WebPageURL(string: url)?.url ?? fallback
    }
}
