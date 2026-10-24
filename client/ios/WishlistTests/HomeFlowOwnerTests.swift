import Shared
import SwiftUI
import XCTest
@testable import Wishlist

/// The continuous-processing screen's owner over the debug Fake (seeds: 분류·목적 확인 3, 정보 보완 3):
/// one start per entry, temporary passes up to the summary, 처음부터 다시 보기, the route policy, the
/// entry-owner lifetime, and the foreground gate (only while the flow is the visible top screen).
final class HomeFlowOwnerTests: XCTestCase {
    @MainActor
    func testStartsOnceAndCountsPositionOverTotal() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = HomeFlowOwner(presenter: runtime.homeFlowPresenter())
        defer { owner.close() }

        let target = HomeFlowTarget(kind: .review, startAnchor: nil)
        owner.startOnce(target)
        owner.startOnce(target)
        await SharedTestRuntime.eventually { owner.state.phase == .active }
        XCTAssertEqual(owner.state.group, .classificationReview)
        XCTAssertEqual(HomeTodoText.position(Int(owner.state.position), of: Int(owner.state.total)), "1 / 3")
        XCTAssertEqual(owner.state.upcoming.count, 2)
    }

    @MainActor
    func testPassesReachTheSummaryAndRestartStartsOver() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owner = HomeFlowOwner(presenter: runtime.homeFlowPresenter())
        defer { owner.close() }

        owner.startOnce(HomeFlowTarget(kind: .fill, startAnchor: nil))
        await SharedTestRuntime.eventually { owner.state.phase == .active }
        XCTAssertEqual(owner.state.group, .informationCompletion)
        for expected in 1...3 {
            let id = owner.state.current?.item.id
            owner.pass()
            await SharedTestRuntime.eventually { owner.state.passed == expected && owner.state.current?.item.id != id }
        }
        await SharedTestRuntime.eventually { owner.state.phase == .done }
        XCTAssertEqual(owner.state.passed, 3)

        owner.restart()
        await SharedTestRuntime.eventually { owner.state.phase == .active && owner.state.passed == 0 }
        XCTAssertEqual(owner.state.position, 1)
        XCTAssertEqual(owner.state.total, 3)
    }

    func testFlowRouteIsAccountScopedWithoutTabBar() {
        let destination = AppDestination.homeFlow(HomeFlowTarget(kind: .review, startAnchor: "a/1"))
        XCTAssertTrue(destination.accountScoped)
        XCTAssertFalse(destination.showsTabBar)
        XCTAssertEqual(destination.route.destination.base as? AppDestination, destination)
        XCTAssertEqual(HomeFlowTarget.Kind(group: .classificationReview), .review)
        XCTAssertEqual(HomeFlowTarget.Kind(group: .informationCompletion), .fill)
        XCTAssertNil(HomeFlowTarget.Kind(group: .analysisInProgress))
    }

    @MainActor
    func testEntryOwnersKeepOneFlowOwnerPerEntryUntilClosed() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let owners = WLEntryOwners(runtime: runtime)
        let owner = owners.homeFlow(4)
        XCTAssertTrue(owner === owners.homeFlow(4))
        owners.close([4])
        XCTAssertFalse(owner === owners.homeFlow(4))
        owners.homeFlow(4).startOnce(HomeFlowTarget(kind: .review, startAnchor: nil))
        try? await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(owners.homeFlow(4).state.phase, .loading) // retired: closed, nothing loads
    }

    func testForegroundOnlyAfterBackgroundAndWhileVisible() {
        var gate = FlowForegroundGate()
        XCTAssertFalse(gate.shouldForeground(.active, visible: true)) // opened while the app was active
        XCTAssertFalse(gate.shouldForeground(.inactive, visible: true))
        XCTAssertFalse(gate.shouldForeground(.active, visible: true)) // Control Center: never left
        XCTAssertFalse(gate.shouldForeground(.background, visible: true))
        XCTAssertTrue(gate.shouldForeground(.active, visible: true))
        _ = gate.shouldForeground(.background, visible: false)
        XCTAssertFalse(gate.shouldForeground(.active, visible: false)) // covered by another screen or tab
    }
}
