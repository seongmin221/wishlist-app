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
    /// Stale `.tmp-<key>` writes (the extension ended between write and rename) moved into place first.
    var recovered: [String] = []

    static let empty = InboxReadOutcome()
}

/// The app side of the app-group inbox (C3-D1): reads every `inbox/*.json` in name order, hands
/// the v1 records to the shared runtime's `importInbox`, and deletes only the files the runtime
/// reported deletable — a crash between import and delete re-imports the same keys, which is a
/// no-op (`reimportingSameRecordIsNoOp`). `directory` is nil when the build has no app-group
/// container (unsigned IOS_TEST builds): the pass then does nothing.
///
/// The writer writes `.tmp-<key>` atomically (complete or absent) and then renames it. An extension
/// ended between the two leaves a complete temporary file: one older than [staleTemporaryAge] is no
/// longer being written, so the pass first renames it into place (or drops it when the record is
/// already there) and imports it like any other record.
struct ShareInboxReader {
    let directory: URL?
    var fileManager: FileManager = .default
    var now: () -> Date = Date.init

    static let staleTemporaryAge: TimeInterval = 60

    private static let log = Logger(subsystem: "app.wishlist.ios", category: "inbox")

    func importPending(_ importer: @escaping ([InboxRecord]) async throws -> InboxImportResult) async -> InboxReadOutcome {
        guard let directory else { return .empty }
        var outcome = InboxReadOutcome()
        outcome.recovered = recoverStaleTemporaries(in: directory)
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return outcome }
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

    private func recoverStaleTemporaries(in directory: URL) -> [String] {
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else { return [] }
        var recovered: [String] = []
        for name in names.sorted() where name.hasPrefix(InboxRecordFile.temporaryPrefix) {
            let temporary = directory.appendingPathComponent(name)
            let modified = (try? fileManager.attributesOfItem(atPath: temporary.path)[.modificationDate]) as? Date
            guard let modified, now().timeIntervalSince(modified) >= Self.staleTemporaryAge else { continue }
            let key = String(name.dropFirst(InboxRecordFile.temporaryPrefix.count))
            let final = directory.appendingPathComponent(InboxRecordFile.fileName(key))
            if fileManager.fileExists(atPath: final.path) {
                try? fileManager.removeItem(at: temporary)
            } else if (try? fileManager.moveItem(at: temporary, to: final)) != nil {
                Self.log.info("inbox: recovered an interrupted write \(key, privacy: .public)")
                recovered.append(key)
            }
        }
        return recovered
    }
}
