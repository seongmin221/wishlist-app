import XCTest
import Shared
@testable import Wishlist

/// Owners per back-stack entry: same id → same owner, `close` closes the Presenter for good, and an
/// entry removed by `replaceTop` gets its owner closed once the transition finishes.
final class WLEntryOwnersTests: XCTestCase {
    @MainActor
    private final class Made {
        var items: [ItemDetailPresenter] = []
        var locals: [LocalSubmissionDetailPresenter] = []
    }

    @MainActor
    private func owners(_ runtime: SharedRuntime, _ made: Made) -> WLEntryOwners {
        WLEntryOwners(
            makeItem: {
                let p = runtime.itemDetailPresenter()
                made.items.append(p)
                return p
            },
            makeLocal: {
                let p = runtime.localSubmissionDetailPresenter()
                made.locals.append(p)
                return p
            }
        )
    }

    @MainActor
    func testSameIdGivesTheSameOwner() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let made = Made()
        let owners = owners(runtime, made)
        defer { owners.close([1, 2]) }

        XCTAssertTrue(owners.itemDetail(1) === owners.itemDetail(1))
        XCTAssertTrue(owners.localDetail(2) === owners.localDetail(2))
        XCTAssertFalse(owners.itemDetail(1) === owners.itemDetail(3))
        owners.close([3])
        XCTAssertEqual(made.items.count, 2)
        XCTAssertEqual(made.locals.count, 1)
    }

    @MainActor
    func testCloseClosesTheOwnersPresenterAndRetryIsIgnored() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let made = Made()
        let owners = owners(runtime, made)
        let owner = owners.itemDetail(7)
        let presenter = try XCTUnwrap(made.items.first)

        owners.close([7])
        owners.close([7]) // idempotent
        owner.load(id: seed.id)
        owner.retry()
        presenter.load(id: seed.id)
        presenter.retry()
        await SharedTestRuntime.stays(for: 0.5) {
            owner.item == nil && presenter.state.value == ItemDetailState.companion.Initial
        }

        // A retired id never gets a working owner back (no new Presenter for the old entry).
        let again = owners.itemDetail(7)
        XCTAssertFalse(again === owner)
        again.load(id: seed.id)
        await SharedTestRuntime.stays(for: 0.3) { again.item == nil && !again.loading }
    }

    /// local → item (replaceTop): the local owner is closed only after the cross-fade finished.
    @MainActor
    func testLocalOwnerRemovedByReplaceTopIsClosed() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let made = Made()
        let owners = owners(runtime, made)
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(AppDestination.local("l1").route, sourceKey: "home/row/l1"))
        nav.finishTransition()
        let localId = try XCTUnwrap(nav.entries(.home).last?.id)
        _ = owners.localDetail(localId)
        let localPresenter = try XCTUnwrap(made.locals.first)

        XCTAssertTrue(nav.replaceTop(AppDestination.item("i1").route))
        let itemId = try XCTUnwrap(nav.entries(.home).last?.id)
        let itemOwner = owners.itemDetail(itemId)
        defer { owners.close([itemId]) }
        // Mid-transition the leaving screen is still drawn: nothing to close yet.
        XCTAssertTrue(nav.isTransitioning)

        nav.finishTransition()
        owners.close(nav.drainRemoved())

        // Closed: an unknown id would otherwise be decided (Gone / RemovedOnServer) by the lookup.
        localPresenter.load(submissionId: "00000000-0000-4000-8000-00000000dead")
        await SharedTestRuntime.stays(for: 0.5) { localPresenter.state.value.outcome == nil }
        XCTAssertFalse(owners.localDetail(localId) === itemOwner)

        // Control: an open local owner decides the same unknown id.
        let open = owners.localDetail(999)
        defer { owners.close([999]) }
        open.load(submissionId: "00000000-0000-4000-8000-00000000dead")
        await SharedTestRuntime.eventually { open.outcome != nil }
    }
}
