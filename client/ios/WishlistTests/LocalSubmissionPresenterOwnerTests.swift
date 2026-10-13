import XCTest
import Shared
@testable import Wishlist

/// The local link owner republishes the Presenter's state and owns its lifetime (Android
/// `LocalSubmissionPresenterOwnerTest`, through the runtime like the C3 owner tests).
final class LocalSubmissionPresenterOwnerTests: XCTestCase {
    private let unknown = "00000000-0000-4000-8000-00000000dead"

    @MainActor
    func testOwnerRepublishesTheOutcomeOfAnUnknownLink() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = LocalSubmissionPresenterOwner(runtime: runtime)
        defer { owner.close() }
        XCTAssertNil(owner.outcome)

        owner.loadOnce(submissionId: unknown)
        await SharedTestRuntime.eventually { owner.outcome != nil }
        XCTAssertNil(owner.row)
        XCTAssertFalse(owner.canDelete)
    }

    @MainActor
    func testLoadOnceKeepsTheFirstLink() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = LocalSubmissionPresenterOwner(runtime: runtime)
        defer { owner.close() }
        owner.loadOnce(submissionId: unknown)
        owner.loadOnce(submissionId: "00000000-0000-4000-8000-00000000beef")
        XCTAssertEqual(owner.requestedId, unknown)
    }

    @MainActor
    func testCloseClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let presenter = runtime.localSubmissionDetailPresenter()
        let owner = LocalSubmissionPresenterOwner(presenter: presenter)

        owner.close()
        owner.close() // idempotent
        owner.loadOnce(submissionId: unknown)
        presenter.load(submissionId: unknown)
        // Closed: the lookup would otherwise decide the unknown id (Gone).
        await SharedTestRuntime.stays(for: 0.5) { owner.outcome == nil && presenter.state.value.outcome == nil }
    }

    @MainActor
    func testDeinitClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let presenter = runtime.localSubmissionDetailPresenter()
        weak var released: LocalSubmissionPresenterOwner?
        do {
            let owner = LocalSubmissionPresenterOwner(presenter: presenter)
            released = owner
        }
        await SharedTestRuntime.eventually { released == nil }
        presenter.load(submissionId: unknown)
        await SharedTestRuntime.stays(for: 0.5) { presenter.state.value.outcome == nil }
    }

    /// "지우지 못했어요" once per failed delete: the flag is cleared by the next view, which re-arms it.
    @MainActor
    func testDeleteFailureIsAnnouncedOncePerRisingEdge() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = LocalSubmissionPresenterOwner(runtime: runtime)
        defer { owner.close() }
        XCTAssertFalse(owner.takeDeleteFailureNotice(deleteFailed: false))
        XCTAssertTrue(owner.takeDeleteFailureNotice(deleteFailed: true))
        XCTAssertFalse(owner.takeDeleteFailureNotice(deleteFailed: true))
        XCTAssertFalse(owner.takeDeleteFailureNotice(deleteFailed: false))
        XCTAssertTrue(owner.takeDeleteFailureNotice(deleteFailed: true))
    }
}
