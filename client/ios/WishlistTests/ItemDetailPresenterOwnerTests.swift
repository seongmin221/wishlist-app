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
    func testOwnerFollowsAccountSwitchesAndRetriesAfterAnError() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let owner = ItemDetailPresenterOwner(runtime: runtime)
        defer { owner.close() }

        owner.load(id: seed.id)
        await SharedTestRuntime.eventually { owner.item?.id == seed.id }

        try await SharedTestRuntime.switchAccount(runtime, to: .apple)
        await SharedTestRuntime.eventually { owner.item == nil && owner.error == nil && !owner.loading }

        owner.retry()
        await SharedTestRuntime.eventually { owner.error?.kind == .notFound }
        XCTAssertNil(owner.item)

        // Error -> retry -> item, once the first account is signed in again (new generation).
        try await SharedTestRuntime.switchAccount(runtime, to: .google)
        await SharedTestRuntime.eventually { owner.error == nil && owner.item == nil }
        owner.retry()
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
}
