import Foundation

/// What a shared text yields (Kotlin `ParsedShare`).
enum ShareExtraction: Equatable {
    case link(String)
    case noLink
    case tooLong
}

/// The share extension's copy of Kotlin `ShareTextParser` (C3-D4); the extension does not link
/// Shared, so the rule is written twice and pinned by the same test vectors on both sides:
/// first match of `https?://[^\s<>"'　]+` (case-insensitive), trailing `.,;:!?` and unpaired
/// closing brackets trimmed repeatedly, an empty host is no link, more than 2048 UTF-16 units is
/// too long. Case and percent-encoding are kept (normalization is the server's job).
enum ShareTextExtractor {
    static let maxUrlLength = 2048

    /// Kotlin's `\s` is the ASCII set (space, \t, \n, \x0B, \f, \r). ICU's `\s` also matches NBSP and
    /// other Unicode spaces, so the class is spelled out instead of using `\s`.
    private static let link: NSRegularExpression = {
        // A constant pattern: a typo fails every extractor test at once.
        try! NSRegularExpression(pattern: #"https?://[^ \t\n\x{0B}\f\r<>"'\x{3000}]+"#, options: [.caseInsensitive])
    }()

    private static let trailingPunctuation = Set(".,;:!?".utf16)
    /// Closers whose unpaired copies are trimmed. Kotlin's map also lists `>` and its quote rule
    /// covers `'` `"`, but the link pattern already excludes those three, so they never reach here.
    private static let openerOf: [UInt16: UInt16] = [
        unit(")"): unit("("),
        unit("]"): unit("["),
        unit("}"): unit("{"),
        0x300D: 0x300C, // 」 「
        0x300F: 0x300E, // 』 『
    ]
    private static let hostEnd = Set("/?#".utf16)

    private static func unit(_ scalar: Unicode.Scalar) -> UInt16 { UInt16(scalar.value) }

    static func extract(_ text: String?) -> ShareExtraction {
        guard let text, !text.isEmpty else { return .noLink }
        let ns = text as NSString
        guard let match = link.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return .noLink }
        // UTF-16 units throughout, like Kotlin `Char`/`String.length`.
        let url = trimTrailing(Array(ns.substring(with: match.range).utf16))
        let afterScheme = url.firstIndex(of: unit(":")).map { url[($0 + 3)...] } ?? []
        let host = afterScheme.prefix { !hostEnd.contains($0) }
        if host.isEmpty { return .noLink }
        let value = String(decoding: url, as: UTF16.self)
        if (value as NSString).length > maxUrlLength { return .tooLong }
        return .link(value)
    }

    private static func trimTrailing(_ candidate: [UInt16]) -> [UInt16] {
        var url = candidate
        while let last = url.last, isTrimmable(last, in: url) { url.removeLast() }
        return url
    }

    private static func isTrimmable(_ last: UInt16, in url: [UInt16]) -> Bool {
        if trailingPunctuation.contains(last) { return true }
        guard let opener = openerOf[last] else { return false }
        return url.filter { $0 == opener }.count < url.filter { $0 == last }.count
    }
}
