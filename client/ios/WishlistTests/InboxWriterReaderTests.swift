import Shared
import SwiftUI
import XCTest
@testable import Wishlist

/// The app-group inbox: the extension's writer (compiled into this target from `ShareExtension/`)
/// and the app's reader, over an injected temporary directory (IOS_TEST builds have no app group).
final class InboxWriterReaderTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("inbox-tests-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("inbox", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory.deletingLastPathComponent())
    }

    private func names() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
    }

    private func record(_ key: String, url: String = "https://a.example/1") -> InboxRecordFile {
        InboxRecordFile(clientSubmissionId: key, sourceUrl: url, sharedAt: "2026-10-07T01:02:03.456Z", accountBinding: nil)
    }

    func testWriteIsAtomicAndReadable() throws {
        let id = UUID(uuidString: "6F9619FF-8B86-D011-B42D-00C04FC964FF")!
        let sharedAt = Date(timeIntervalSince1970: 1_791_334_923.456)
        let written = try ShareInboxWriter(directory: directory)
            .write(sourceUrl: "https://musinsa.com/p/1", accountBinding: "fake-google-0001", sharedAt: sharedAt, id: id)

        let key = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
        XCTAssertEqual(try names(), ["\(key).json"], "one final file, no temporary file left")
        XCTAssertEqual(written.clientSubmissionId, key)
        XCTAssertEqual(written.sharedAt, "2026-10-07T01:02:03.456Z")

        let data = try Data(contentsOf: directory.appendingPathComponent("\(key).json"))
        XCTAssertEqual(InboxRecordFile.parse(data), .record(written))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["v"] as? Int, 1)
        XCTAssertEqual(json["sourceUrl"] as? String, "https://musinsa.com/p/1")
        XCTAssertEqual(json["accountBinding"] as? String, "fake-google-0001")
    }

    func testUnboundWriteStoresAnExplicitNullBinding() throws {
        let written = try ShareInboxWriter(directory: directory).write(sourceUrl: "https://a.example/1", accountBinding: nil)
        let data = try Data(contentsOf: directory.appendingPathComponent("\(written.clientSubmissionId).json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertTrue(json["accountBinding"] is NSNull)
        XCTAssertEqual(written.clientSubmissionId, written.clientSubmissionId.lowercased())
    }

    func testFileDeletedOnlyAfterImport() async throws {
        let writer = ShareInboxWriter(directory: directory)
        let kept = try writer.write(sourceUrl: "https://a.example/kept", accountBinding: nil)
        let imported = try writer.write(sourceUrl: "https://a.example/imported", accountBinding: "fake-apple-0001")
        var offered: [InboxRecord] = []

        let outcome = await ShareInboxReader(directory: directory).importPending { records in
            offered = records
            // Nothing is deleted before the importer answered.
            XCTAssertEqual(try self.names().count, 2)
            return InboxImportResult(deletable: [imported.clientSubmissionId], retained: [kept.clientSubmissionId])
        }

        XCTAssertEqual(Set(offered.map(\.clientSubmissionId)), [kept.clientSubmissionId, imported.clientSubmissionId])
        let importedRecord = try XCTUnwrap(offered.first { $0.clientSubmissionId == imported.clientSubmissionId })
        XCTAssertEqual(importedRecord.sourceUrl, "https://a.example/imported")
        XCTAssertEqual(importedRecord.sharedAtIso, imported.sharedAt)
        XCTAssertEqual(importedRecord.accountBinding, "fake-apple-0001")
        XCTAssertNil(offered.first { $0.clientSubmissionId == kept.clientSubmissionId }?.accountBinding)
        XCTAssertEqual(try names(), ["\(kept.clientSubmissionId).json"])
        XCTAssertEqual(outcome.deleted, [imported.clientSubmissionId])
        XCTAssertEqual(outcome.kept, [kept.clientSubmissionId])
    }

    func testImporterFailureKeepsEveryFile() async throws {
        struct Boom: Error {}
        _ = try ShareInboxWriter(directory: directory).write(sourceUrl: "https://a.example/1", accountBinding: nil)
        let outcome = await ShareInboxReader(directory: directory).importPending { _ in throw Boom() }
        XCTAssertEqual(try names().count, 1)
        XCTAssertEqual(outcome.deleted, [])
        XCTAssertEqual(outcome.kept.count, 1)
    }

    func testRecordsAreOfferedInFileNameOrder() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for key in ["c0000000-0000-4000-8000-000000000003", "a0000000-0000-4000-8000-000000000001", "b0000000-0000-4000-8000-000000000002"] {
            try JSONEncoder().encode(record(key)).write(to: directory.appendingPathComponent("\(key).json"))
        }
        var offered: [String] = []
        _ = await ShareInboxReader(directory: directory).importPending { records in
            offered = records.map(\.clientSubmissionId)
            return InboxImportResult(deletable: [], retained: offered)
        }
        XCTAssertEqual(offered, ["a0000000-0000-4000-8000-000000000001", "b0000000-0000-4000-8000-000000000002", "c0000000-0000-4000-8000-000000000003"])
    }

    func testCorruptFileIsReportedDeletable() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let corruptKey = "d0000000-0000-4000-8000-000000000004"
        try Data("{".utf8).write(to: directory.appendingPathComponent("\(corruptKey).json"))
        // A v1 file missing a required field is just as unreadable.
        try Data(#"{"v":1,"clientSubmissionId":"x"}"#.utf8).write(to: directory.appendingPathComponent("partial.json"))
        var offered: [InboxRecord]?

        let outcome = await ShareInboxReader(directory: directory).importPending { records in
            offered = records
            return InboxImportResult(deletable: [], retained: [])
        }

        XCTAssertEqual(outcome.corrupt.sorted(), [corruptKey, "partial"])
        XCTAssertEqual(try names(), [])
        XCTAssertNil(offered, "nothing readable: the importer is not called")
    }

    func testUnknownVersionIsKept() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let key = "e0000000-0000-4000-8000-000000000005"
        let future = #"{"v":2,"clientSubmissionId":"\#(key)","payload":{"kind":"image"}}"#
        try Data(future.utf8).write(to: directory.appendingPathComponent("\(key).json"))

        let outcome = await ShareInboxReader(directory: directory).importPending { records in
            XCTAssertEqual(records, [])
            return InboxImportResult(deletable: [], retained: [])
        }

        XCTAssertEqual(outcome.unknownVersion, [key])
        XCTAssertEqual(try names(), ["\(key).json"])
        XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("\(key).json"), encoding: .utf8), future, "untouched")
    }

    func testTemporaryFilesAndOtherNamesAreIgnored() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let key = "f0000000-0000-4000-8000-000000000006"
        try JSONEncoder().encode(record(key)).write(to: directory.appendingPathComponent(".tmp-\(key)"))
        try Data("note".utf8).write(to: directory.appendingPathComponent("README.txt"))

        let outcome = await ShareInboxReader(directory: directory).importPending { records in
            XCTAssertEqual(records, [])
            return InboxImportResult(deletable: [], retained: [])
        }
        XCTAssertEqual(outcome.corrupt, [])
        XCTAssertEqual(try names(), [".tmp-\(key)", "README.txt"], "an in-progress write is never touched")
    }

    func testMissingDirectoryOrContainerReadsNothing() async {
        var called = false
        let missing = await ShareInboxReader(directory: directory).importPending { _ in
            called = true
            return InboxImportResult(deletable: [], retained: [])
        }
        let disabled = await ShareInboxReader(directory: nil).importPending { _ in
            called = true
            return InboxImportResult(deletable: [], retained: [])
        }
        XCTAssertFalse(called)
        XCTAssertEqual(missing, .empty)
        XCTAssertEqual(disabled, .empty)
    }

    func testWriteFailsWhenTheDirectoryCannotBeCreated() throws {
        let blocker = directory.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: blocker.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("file, not a folder".utf8).write(to: blocker)
        XCTAssertThrowsError(try ShareInboxWriter(directory: directory).write(sourceUrl: "https://a.example/1", accountBinding: nil))
    }

    /// IOS_TEST runs inside the host app, which shares the installed debug app's container: the app's
    /// signals (inbox import, refresh, network flush) stay off under XCTest so tests never import or send.
    @MainActor
    func testAppSignalsDoNothingUnderXCTest() async throws {
        XCTAssertTrue(AppSignals.runningUnderXCTest)
        let written = try ShareInboxWriter(directory: directory).write(sourceUrl: "https://a.example/1", accountBinding: nil)
        let runtime = SharedRuntimeFactory.shared.create(
            bindings: RepositoryBindings(buildMode: .theRelease, backends: AppRuntimeConfig.allBackends(.unavailable)),
            remote: nil
        )
        defer { runtime.close() }
        let signals = AppSignals(runtime: runtime, inboxDirectory: directory)
        signals.start()
        signals.scenePhaseChanged(.active)
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(try names(), ["\(written.clientSubmissionId).json"], "the inbox file was not imported")
    }
}

/// Scene phases that are a launch or a return from the background (Android `ForegroundTransitionsTest`).
final class ForegroundTransitionsTests: XCTestCase {
    func testLaunchIsAForegroundOnce() {
        var transitions = ForegroundTransitions()
        XCTAssertFalse(transitions.isForeground(.inactive)) // the launch passes through inactive
        XCTAssertTrue(transitions.isForeground(.active))
        XCTAssertFalse(transitions.isForeground(.active))
    }

    func testInactiveAndBackIsNotAForeground() {
        // Control Center, Notification Center, a Face ID sheet: the app never left.
        var transitions = ForegroundTransitions()
        XCTAssertTrue(transitions.isForeground(.active))
        XCTAssertFalse(transitions.isForeground(.inactive))
        XCTAssertFalse(transitions.isForeground(.active))
    }

    func testReturnFromTheBackgroundIsAForeground() {
        var transitions = ForegroundTransitions()
        XCTAssertTrue(transitions.isForeground(.active))
        for phase: ScenePhase in [.inactive, .background, .inactive] {
            XCTAssertFalse(transitions.isForeground(phase))
        }
        XCTAssertTrue(transitions.isForeground(.active))
    }
}
