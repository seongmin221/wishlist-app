import Foundation

/// One share in the app-group inbox: `inbox/<clientSubmissionId>.json`, written once by the share
/// extension and deleted by the app only after the shared runtime imported it (Review Focus 2).
///
/// Format v1 (`v` is required; a reader keeps files of an unknown version untouched for a future app):
/// `{"v":1,"clientSubmissionId":"<lowercase uuid>","sourceUrl":"<extracted>","sharedAt":"2026-10-07T01:02:03.456Z","accountBinding":null}`
/// `sharedAt` is UTC with milliseconds; `accountBinding` is the account signed in at share time, or null.
struct InboxRecordFile: Codable, Equatable {
    static let version = 1
    static let fileExtension = "json"
    /// In-progress writes (`inbox/.tmp-<key>`); the app moves one into place only once it is stale
    /// (`ShareInboxReader.staleTemporaryAge`).
    static let temporaryPrefix = ".tmp-"

    let v: Int
    let clientSubmissionId: String
    let sourceUrl: String
    let sharedAt: String
    let accountBinding: String?

    init(clientSubmissionId: String, sourceUrl: String, sharedAt: String, accountBinding: String?) {
        v = Self.version
        self.clientSubmissionId = clientSubmissionId
        self.sourceUrl = sourceUrl
        self.sharedAt = sharedAt
        self.accountBinding = accountBinding
    }

    private enum CodingKeys: String, CodingKey { case v, clientSubmissionId, sourceUrl, sharedAt, accountBinding }

    /// Writes `accountBinding` as an explicit `null` (the synthesized encoder would omit it).
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(clientSubmissionId, forKey: .clientSubmissionId)
        try c.encode(sourceUrl, forKey: .sourceUrl)
        try c.encode(sharedAt, forKey: .sharedAt)
        try c.encode(accountBinding, forKey: .accountBinding)
    }

    static func fileName(_ key: String) -> String { "\(key).\(fileExtension)" }

    /// `2026-10-07T01:02:03.456Z`: whole milliseconds, rounded once, so the text never depends on
    /// floating-point formatting.
    static func timestamp(_ date: Date) -> String {
        let millis = max(0, Int64((date.timeIntervalSince1970 * 1000).rounded()))
        let (seconds, fraction) = millis.quotientAndRemainder(dividingBy: 1000)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let whole = formatter.string(from: Date(timeIntervalSince1970: TimeInterval(seconds))) // ...THH:mm:ssZ
        return String(whole.dropLast()) + String(format: ".%03lldZ", fraction)
    }

    /// Classifies a file's bytes: a v1 record, a newer version to leave alone, or unreadable.
    static func parse(_ data: Data) -> InboxFileContent {
        struct Versioned: Decodable { let v: Int }
        let decoder = JSONDecoder()
        guard let versioned = try? decoder.decode(Versioned.self, from: data) else { return .corrupt }
        guard versioned.v == version else { return .unknownVersion(versioned.v) }
        guard let record = try? decoder.decode(InboxRecordFile.self, from: data) else { return .corrupt }
        return .record(record)
    }
}

enum InboxFileContent: Equatable {
    case record(InboxRecordFile)
    case unknownVersion(Int)
    case corrupt
}
