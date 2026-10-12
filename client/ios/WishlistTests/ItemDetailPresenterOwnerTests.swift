import XCTest
import Shared
@testable import Wishlist

/// The main-actor owner republishes the Presenter's state and owns its lifetime (no UI).
final class ItemDetailPresenterOwnerTests: XCTestCase {
    @MainActor
    func testOwnerCollectsConcreteState() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }
        XCTAssertNil(owner.item)
        XCTAssertFalse(owner.loading)

        owner.load(id: seed.id)
        await SharedTestRuntime.eventually { owner.item?.id == seed.id && !owner.loading }
        XCTAssertEqual(owner.item?.product.name, seed.product.name)
        XCTAssertNil(owner.error)
    }

    @MainActor
    func testOwnerFollowsAccountSwitchesAndForgetsThePreviousItem() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }

        owner.load(id: seed.id)
        await SharedTestRuntime.eventually { owner.item?.id == seed.id }

        try await SharedTestRuntime.switchAccount(runtime, to: .apple)
        await SharedTestRuntime.eventually { owner.item == nil && owner.error == nil && !owner.loading }

        // D3: the previous account's item is never requested again; retry does nothing.
        owner.retry()
        await SharedTestRuntime.stays(for: 0.3) { owner.item == nil && owner.error == nil && !owner.loading }

        // Back to the first account (a new generation): still cleared, and a fresh load finds the item.
        try await SharedTestRuntime.switchAccount(runtime, to: .google)
        await SharedTestRuntime.eventually { owner.error == nil && owner.item == nil }
        owner.load(id: seed.id)
        await SharedTestRuntime.eventually { owner.item?.id == seed.id && !owner.loading }
        XCTAssertNil(owner.error)
    }

    @MainActor
    func testCloseCancelsCollectionAndClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let presenter = runtime.itemDetailPresenter()
        let owner = ItemDetailPresenterOwner(presenter: presenter)

        owner.close()
        owner.close() // idempotent
        owner.load(id: seed.id)
        presenter.load(id: seed.id)
        await SharedTestRuntime.stays(for: 0.5) {
            owner.item == nil && presenter.state.value == ItemDetailState.companion.Initial
        }
    }

    @MainActor
    func testDeinitEndsCollectionAndClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let presenter = runtime.itemDetailPresenter()
        weak var released: ItemDetailPresenterOwner?
        do {
            let owner = ItemDetailPresenterOwner(presenter: presenter)
            released = owner
            owner.load(id: seed.id)
            await SharedTestRuntime.eventually { owner.item?.id == seed.id }
        }
        // The collection task holds the owner weakly, so dropping the last reference ends it.
        await SharedTestRuntime.eventually { released == nil }

        try await SharedTestRuntime.switchAccount(runtime, to: .apple)
        presenter.load(id: seed.id)
        await SharedTestRuntime.stays(for: 0.5) { presenter.state.value.item?.id == seed.id }
    }

    // MARK: - C4 screen support

    func testCloseRulesFollowTheAccountAndTheSignedOutRestore() {
        let unauthenticated = ClientError(kind: .unauthenticated, code: nil, requestId: nil, currentVersion: nil, retryAfterSeconds: nil)
        // Initial before the first answer is the load not having started yet.
        XCTAssertFalse(ItemDetailPresenterOwner.shouldClose(item: nil, loading: false, error: nil, seenWork: false))
        XCTAssertTrue(ItemDetailPresenterOwner.shouldClose(item: nil, loading: false, error: nil, seenWork: true))
        XCTAssertTrue(ItemDetailPresenterOwner.shouldClose(item: nil, loading: false, error: unauthenticated, seenWork: false))
        XCTAssertFalse(ItemDetailPresenterOwner.shouldClose(item: nil, loading: true, error: unauthenticated, seenWork: true))
        for kind in ErrorKind.allCases where kind != .unauthenticated {
            let error = ClientError(kind: kind, code: nil, requestId: nil, currentVersion: nil, retryAfterSeconds: nil)
            XCTAssertFalse(ItemDetailPresenterOwner.shouldClose(item: nil, loading: false, error: error, seenWork: true), kind.name)
        }
        XCTAssertFalse(ItemDetailPresenterOwner.shouldClose(item: nil, loading: true, error: nil, seenWork: true))
    }

    @MainActor
    func testLoadOnceLoadsASingleTimeAndRefreshAndWaitKeepsTheItem() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }

        owner.loadOnce(id: seed.id)
        await SharedTestRuntime.eventually { owner.item?.id == seed.id && !owner.loading }
        owner.loadOnce(id: "00000000-0000-4000-8000-00000000dead") // a recreated view asks again: ignored
        await SharedTestRuntime.stays(for: 0.3) { owner.item?.id == seed.id && !owner.loading && owner.error == nil }

        await owner.refreshAndWait()
        XCTAssertFalse(owner.loading)
        XCTAssertEqual(owner.item?.id, seed.id)
    }

    /// Ruling 2: nothing shown (no load, or the account moved on) means nothing to refresh: the pull ends at once.
    @MainActor
    func testRefreshAndWaitReturnsAtOnceWithNothingShown() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }
        let start = Date()
        await owner.refreshAndWait()
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.5)
    }

    /// The short notice comes once per new failure while an item is shown (the marker lives on the owner,
    /// so a recreated view does not announce the same failure again).
    @MainActor
    func testRefreshFailureIsAnnouncedOncePerError() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }
        let first = ClientError(kind: .network, code: nil, requestId: nil, currentVersion: nil, retryAfterSeconds: nil)
        let second = ClientError(kind: .network, code: nil, requestId: nil, currentVersion: nil, retryAfterSeconds: nil)

        XCTAssertFalse(owner.takeRefreshNotice(item: nil, loading: false, error: first))
        XCTAssertFalse(owner.takeRefreshNotice(item: seed, loading: true, error: first))
        XCTAssertTrue(owner.takeRefreshNotice(item: seed, loading: false, error: first))
        XCTAssertFalse(owner.takeRefreshNotice(item: seed, loading: false, error: first))
        XCTAssertFalse(owner.takeRefreshNotice(item: seed, loading: false, error: nil))
        XCTAssertTrue(owner.takeRefreshNotice(item: seed, loading: false, error: second))
    }

    /// Only a return from the background refreshes; the screen opens while the app is active, and
    /// `.inactive → .active` (Control Center) is not a return.
    @MainActor
    func testForegroundReturnRefreshesOnlyAfterTheBackground() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }
        XCTAssertFalse(owner.scenePhaseChanged(.active))
        XCTAssertFalse(owner.scenePhaseChanged(.inactive))
        XCTAssertFalse(owner.scenePhaseChanged(.active))
        XCTAssertFalse(owner.scenePhaseChanged(.background))
        XCTAssertTrue(owner.scenePhaseChanged(.active))
        XCTAssertFalse(owner.scenePhaseChanged(.active))
    }
}
