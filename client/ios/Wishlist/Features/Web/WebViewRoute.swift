import Foundation

/// The only way to build `AppDestination.web`: an `http`/`https` URL with a non-empty host (Android
/// `WebViewRoute.of`). Anything else is nil, and the "원본 보기" button stays disabled.
struct WebPageURL: Hashable {
    let url: URL

    init?(_ url: URL) {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host(percentEncoded: false), !host.isEmpty,
              !host.contains(where: { $0.isWhitespace || $0 == "\\" })
        else { return nil }
        self.url = url
    }

    /// Rejects whitespace, control characters and backslashes before parsing (iOS 17 `URL(string:)` would
    /// otherwise percent-encode them into a different URL).
    init?(string: String) {
        guard !string.unicodeScalars.contains(where: {
            CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) || $0 == "\\"
        }),
            let url = URL(string: string)
        else { return nil }
        self.init(url)
    }
}
