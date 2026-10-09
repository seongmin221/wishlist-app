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
/// closing brackets trimmed repeatedly (one pass, bracket counts kept current), an empty host
/// (authority without userinfo and port, Kotlin `hostOf`) is no link, more than 2048 UTF-16 units is
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
    /// Closers whose unpaired copies are trimmed (the same map as Kotlin's). The link pattern already
    /// excludes `>` `'` `"`, so they never reach here.
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
        if host(of: url).isEmpty { return .noLink }
        let value = String(decoding: url, as: UTF16.self)
        if (value as NSString).length > maxUrlLength { return .tooLong }
        return .link(value)
    }

    /// The authority after `://` up to `/ ? #`, without userinfo (up to the last `@`) and port.
    private static func host(of url: [UInt16]) -> ArraySlice<UInt16> {
        guard let colon = url.firstIndex(of: unit(":")), colon + 3 <= url.count else { return [] }
        let authority = url[(colon + 3)...].prefix { !hostEnd.contains($0) }
        let afterUser = authority.lastIndex(of: unit("@")).map { authority[($0 + 1)...] } ?? authority
        return afterUser.prefix { $0 != unit(":") }
    }

    /// Linear: each bracket's count is taken once and kept current as closers are dropped.
    private static func trimTrailing(_ candidate: [UInt16]) -> [UInt16] {
        let brackets = Set(openerOf.keys).union(openerOf.values)
        var count: [UInt16: Int] = [:]
        for unit in candidate where brackets.contains(unit) { count[unit, default: 0] += 1 }
        var end = candidate.count
        while end > 0 {
            let last = candidate[end - 1]
            if trailingPunctuation.contains(last) {
                end -= 1
            } else if let opener = openerOf[last], count[opener, default: 0] < count[last, default: 0] {
                count[last, default: 0] -= 1
                end -= 1
            } else {
                break
            }
        }
        return Array(candidate[..<end])
    }
}
