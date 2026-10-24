import Observation
import Shared
import SwiftUI

/// The continuous-processing route value: which flow, and the carousel preview's anchor to start at
/// (nil: from the first card). Only review and fill open a flow (분류 중 has none).
struct HomeFlowTarget: Hashable {
    enum Kind: Hashable {
        /// FHomeReviewFlow (CLASSIFICATION_REVIEW).
        case review
        /// FHomeFillFlow (INFORMATION_COMPLETION).
        case fill

        init?(group: HomeActionGroup) {
            switch group {
            case .classificationReview: self = .review
            case .informationCompletion: self = .fill
            default: return nil
            }
        }

        var group: HomeActionGroup {
            switch self {
            case .review: .classificationReview
            case .fill: .informationCompletion
            }
        }
    }

    let kind: Kind
    let startAnchor: String?
}

/// When the flow reads its window again: a real return from the background (`ForegroundTransitions`;
/// the screen opens while the app is active) while the flow is the visible top screen. Pure.
struct FlowForegroundGate {
    private var transitions: ForegroundTransitions = {
        var t = ForegroundTransitions()
        _ = t.isForeground(.active)
        return t
    }()

    mutating func shouldForeground(_ phase: ScenePhase, visible: Bool) -> Bool {
        transitions.isForeground(phase) && visible
    }
}

/// One flow entry's `HomeFlowPresenter` (closed with the entry, `WLEntryOwners`). A session is this
/// entry's lifetime: passes live in the Presenter until the screen closes or the account changes.
@MainActor
@Observable
final class HomeFlowOwner: CategoryStateOwner<HomeFlowState> {
    @ObservationIgnored private nonisolated(unsafe) let presenter: HomeFlowPresenter
    @ObservationIgnored private var started = false
    @ObservationIgnored private var gate = FlowForegroundGate()

    init(presenter: HomeFlowPresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }

    /// The entry's first appearance starts the flow; a recreated view does not start it again.
    func startOnce(_ target: HomeFlowTarget) {
        if started { return }
        started = true
        presenter.start(group: target.kind.group, startAnchor: target.startAnchor)
    }

    /// 확정·보류 / 건너뛰기: a temporary pass (nothing saved in C7).
    func pass() { presenter.pass() }
    func restart() { presenter.restart() }
    func retry() { presenter.retry() }

    /// Scene phase changes while the screen is drawn; only a foreground return while visible reads again.
    func scenePhaseChanged(_ phase: ScenePhase, visible: Bool) {
        if gate.shouldForeground(phase, visible: visible) { presenter.foreground() }
    }

    /// The flow screen is the home tab's top entry (another tab or a pushed web view covers it otherwise).
    static func isVisible(entryID: Int?, navigator: WLNavigator) -> Bool {
        navigator.currentTab == .home && entryID != nil && navigator.entries(.home).last?.id == entryID
    }
}

#if DEBUG
/// C7 screenshot hook: `-wl.c7.screen <home|home-expanded|review|review-drag|review-done|fill|fill-done|category>`
/// signs the Fake in and opens that state (no UI driver on the simulator). `category` opens 헤드폰 (with
/// `-wl.fake.longCategory C026` a long list) and pulls a refresh anchored on its 9th card, so the restore runs.
enum C7DebugScreens {
    static let key = "wl.c7.screen"

    static var screen: String? { DebugLaunchHooks.string(of: key, in: ProcessInfo.processInfo.arguments) }
    /// Home cards open on launch for `home-expanded`.
    static var expandsHome: Bool { screen == "home-expanded" }
    /// The front card starts dragged this far for `review-drag` (mid-swipe).
    static var initialDrag: CGFloat { screen == "review-drag" ? 120 : 0 }

    @MainActor
    static func open(_ screen: String, runtime: SharedRuntime, navigator: WLNavigator, owners: WLEntryOwners) async {
        await runtime.purposeUntilReady()
        if runtime.auth().account.value == nil { _ = try? await runtime.auth().signIn(provider: .google) }
        try? await Task.sleep(for: .milliseconds(800))
        switch screen {
        case "review", "review-drag", "review-done": await flow(.review, done: screen == "review-done", navigator, owners)
        case "fill", "fill-done": await flow(.fill, done: screen == "fill-done", navigator, owners)
        case "category": await category(navigator, owners)
        default: break
        }
    }

    @MainActor
    private static func flow(_ kind: HomeFlowTarget.Kind, done: Bool, _ navigator: WLNavigator, _ owners: WLEntryOwners) async {
        navigator.push(AppDestination.homeFlow(HomeFlowTarget(kind: kind, startAnchor: nil)).route, sourceKey: "debug/flow")
        guard done, let id = navigator.entries(.home).last?.id else { return }
        let owner = owners.homeFlow(id)
        owner.startOnce(HomeFlowTarget(kind: kind, startAnchor: nil))
        for _ in 0..<3 {
            for _ in 0..<100 where owner.state.phase != .active && owner.state.phase != .done {
                try? await Task.sleep(for: .milliseconds(50))
            }
            owner.pass()
            try? await Task.sleep(for: .milliseconds(150))
        }
    }

    @MainActor
    private static func category(_ navigator: WLNavigator, _ owners: WLEntryOwners) async {
        navigator.selectTab(.category)
        try? await Task.sleep(for: .milliseconds(600))
        let target = CategoryListTarget(categoryId: "C026", name: "헤드폰", parentId: "G003", parentName: "디지털·IT", isCustom: false)
        navigator.push(AppDestination.categoryList(target).route, sourceKey: "debug/category")
        try? await Task.sleep(for: .milliseconds(600))
        guard let id = navigator.entries(.category).last?.id else { return }
        let owner = owners.categoryList(id)
        for _ in 0..<100 where owner.state.items.count < 10 || owner.plan.laidIds.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        try? await Task.sleep(for: .seconds(1))
        guard owner.state.items.count > 8 else { return }
        await owner.refreshAndWait(anchor: owner.state.items[8].id)
    }
}
#endif
