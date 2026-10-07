import Foundation
import OSLog
import Shared

/// What one inbox pass did, by key (the file name without `.json`).
struct InboxReadOutcome: Equatable {
    /// Imported (or permanently invalid per the runtime) and removed.
    var deleted: [String] = []
    /// Offered but retained by the runtime (store not ready or failed), or the importer threw: retried next pass.
    var kept: [String] = []
    /// Unreadable bytes: removed without being offered.
    var corrupt: [String] = []
    /// A newer format this app cannot read: left untouched for a future version.
    var unknownVersion: [String] = []

    static let empty = InboxReadOutcome()
}

/// The app side of the app-group inbox (C3-D1): reads every `inbox/*.json` in name order, hands
/// the v1 records to the shared runtime's `importInbox`, and deletes only the files the runtime
/// reported deletable — a crash between import and delete re-imports the same keys, which is a
/// no-op (`reimportingSameRecordIsNoOp`). `directory` is nil when the build has no app-group
/// container (unsigned IOS_TEST builds): the pass then does nothing.
struct ShareInboxReader {
    let directory: URL?
    var fileManager: FileManager = .default

    private static let log = Logger(subsystem: "app.wishlist.ios", category: "inbox")

    func importPending(_ importer: @escaping ([InboxRecord]) async throws -> InboxImportResult) async -> InboxReadOutcome {
        guard let directory else { return .empty }
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return .empty }
        var outcome = InboxReadOutcome()
        var records: [InboxRecord] = []
        var files: [String: [URL]] = [:]
        for name in names.sorted() where !name.hasPrefix(".") && name.hasSuffix(".\(InboxRecordFile.fileExtension)") {
            let url = directory.appendingPathComponent(name)
            let key = String(name.dropLast(InboxRecordFile.fileExtension.count + 1))
            guard let data = try? Data(contentsOf: url) else { continue } // vanished or unreadable now: next pass
            switch InboxRecordFile.parse(data) {
            case .record(let file):
                if files[file.clientSubmissionId] == nil {
                    records.append(InboxRecord(clientSubmissionId: file.clientSubmissionId, sourceUrl: file.sourceUrl,
                                               sharedAtIso: file.sharedAt, accountBinding: file.accountBinding))
                }
                files[file.clientSubmissionId, default: []].append(url)
            case .unknownVersion(let version):
                Self.log.info("inbox: keeping \(name, privacy: .public) (format v\(version))")
                outcome.unknownVersion.append(key)
            case .corrupt:
                Self.log.error("inbox: removing unreadable \(name, privacy: .public)")
                try? fileManager.removeItem(at: url)
                outcome.corrupt.append(key)
            }
        }
        guard !records.isEmpty else { return outcome }

        let result: InboxImportResult
        do {
            result = try await importer(records)
        } catch {
            Self.log.error("inbox: import failed, keeping \(records.count) file(s): \(String(describing: error), privacy: .public)")
            outcome.kept = records.map(\.clientSubmissionId)
            return outcome
        }
        let deletable = Set(result.deletable)
        for record in records {
            let key = record.clientSubmissionId
            if deletable.contains(key) {
                for url in files[key] ?? [] { try? fileManager.removeItem(at: url) }
                outcome.deleted.append(key)
            } else {
                outcome.kept.append(key)
            }
        }
        return outcome
    }
}
