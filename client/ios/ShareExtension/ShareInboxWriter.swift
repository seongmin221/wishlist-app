import Foundation
#if WISHLIST_TESTS
// The test target compiles this extension file directly; the app-group types come from the app module there.
@testable import Wishlist
#endif

/// Writes one share as `inbox/<key>.json` in the app group (C3-D1 C안). The file appears whole or
/// not at all: the bytes go to `inbox/.tmp-<key>` (itself an atomic write) and are then moved to
/// the final name, so the app's reader never sees a partial record. A new share always gets a new
/// key, even for the same URL (Review Focus 4).
struct ShareInboxWriter {
    let directory: URL
    var fileManager: FileManager = .default

    @discardableResult
    func write(sourceUrl: String, accountBinding: String?, sharedAt: Date = Date(), id: UUID = UUID()) throws -> InboxRecordFile {
        let record = InboxRecordFile(
            clientSubmissionId: id.uuidString.lowercased(),
            sourceUrl: sourceUrl,
            sharedAt: InboxRecordFile.timestamp(sharedAt),
            accountBinding: accountBinding
        )
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let key = record.clientSubmissionId
        let temporary = directory.appendingPathComponent(InboxRecordFile.temporaryPrefix + key)
        let final = directory.appendingPathComponent(InboxRecordFile.fileName(key))
        try JSONEncoder().encode(record).write(to: temporary, options: .atomic)
        do {
            try fileManager.moveItem(at: temporary, to: final)
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
        return record
    }
}
