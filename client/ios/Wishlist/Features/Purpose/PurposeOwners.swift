import Observation
import Shared
import SwiftUI
import UIKit

struct PurposeFactories {
    let home: () -> PurposeHomePresenter
    let detail: () -> PurposeDetailPresenter
    let form: () -> PurposeFormPresenter
    init(runtime: SharedRuntime) {
        home = { runtime.purposeHomePresenter() }
        detail = { runtime.purposeDetailPresenter() }
        form = { runtime.purposeFormPresenter() }
    }
}

@MainActor @Observable
final class PurposeHomeOwner: CategoryStateOwner<PurposeHomeState> {
    @ObservationIgnored private nonisolated(unsafe) let presenter: PurposeHomePresenter
    @ObservationIgnored private var started = false
    @ObservationIgnored private var wasActive = true
    init(presenter: PurposeHomePresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }
    func loadOnce() { guard !started else { return }; started = true; presenter.load() }
    func refresh() { presenter.refresh() }
    func retry() { presenter.retry() }
    func refreshAndWait() async { try? await presenter.refreshNow() }
    func scenePhaseChanged(_ phase: ScenePhase) {
        let active = phase == .active
        if active && !wasActive { refresh() }
        wasActive = active
    }
}

@MainActor @Observable
final class PurposeDetailOwner: CategoryStateOwner<PurposeDetailState> {
    @ObservationIgnored private nonisolated(unsafe) let presenter: PurposeDetailPresenter
    @ObservationIgnored private var started = false
    @ObservationIgnored private var wasVisible = false
    @ObservationIgnored private var column: (width: CGFloat, scale: CGFloat)?
    @ObservationIgnored private var sizeTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var deadlineTask: Task<Void, Never>?
    @ObservationIgnored private var itemsByID: [String: WishlistItem] = [:]
    private(set) var plan = PhotoPagePlan()
    /// Editing belongs to the back-stack entry, including time covered by another tab or item route.
    var form: PurposeFormOwner?
    init(presenter: PurposeDetailPresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }
    func loadOnce(_ id: String) { guard !started else { return }; started = true; presenter.load(id: id) }
    static func isVisible(entryID: Int?, navigator: WLNavigator, phase: ScenePhase) -> Bool {
        phase == .active && navigator.currentTab == .purpose && entryID != nil
            && navigator.entries(.purpose).last?.id == entryID
    }
    /// Retained layers stay mounted: only a hidden-to-front transition starts a new read.
    func navigationChanged(id: String, entryID: Int?, navigator: WLNavigator, phase: ScenePhase) {
        let visible = Self.isVisible(entryID: entryID, navigator: navigator, phase: phase)
        defer { wasVisible = visible }
        guard visible, !wasVisible else { return }
        if started { refresh() } else { loadOnce(id) }
    }
    func refresh() { presenter.refresh() }
    func refreshAndWait() async { try? await presenter.refreshNow() }
    func retry() { presenter.retry() }
    func loadMore() { presenter.loadMore() }
    func applyEdited(_ detail: PurposeDetail) { presenter.applyEdited(detail: detail) }
    /// Preserve the server snapshot learned during conflict recovery before destroying the draft.
    @discardableResult
    func finishEditing() -> Bool {
        guard let form else { return false }
        let recovered = form.state.recoveredDetail
        if let recovered { applyEdited(recovered) }
        form.close()
        self.form = nil
        return recovered != nil
    }
    func item(_ id: String) -> WishlistItem? { itemsByID[id] }
    func setColumn(width: CGFloat, scale: CGFloat) {
        guard width > 0, column?.width != width || column?.scale != scale else { return }
        column = (width, scale)
        fetchSizes()
    }
    func photo(_ url: URL) async -> (image: UIImage, source: CGSize)? {
        guard let column else { return nil }
        return await RemoteImageLoader.shared.image(for: url, width: column.width, scale: column.scale,
            maxPixels: PhotoPagePlan.maxPixels(columnPixels: column.width * column.scale))
    }
    override func applied(_ state: PurposeDetailState) {
        itemsByID = Dictionary(state.items.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let next = state.items.map {
            let url = remotePhotoURL($0.product.imageUrl)
            return PhotoPagePlan.Item(id: $0.id, hasPhoto: url != nil, photo: url?.absoluteString)
        }
        guard next != plan.knownItems else { return }
        let before = plan.knownItems
        plan.sync(next, now: Date())
        if !plan.knownItems.starts(with: before) {
            sizeTasks.values.forEach { $0.cancel() }; sizeTasks = [:]
        }
        fetchSizes(); scheduleDeadline()
    }
    private func fetchSizes() {
        guard column != nil else { return }
        for id in plan.sizesNeeded where sizeTasks[id] == nil {
            guard let url = remotePhotoURL(item(id)?.product.imageUrl) else { continue }
            sizeTasks[id] = Task { @MainActor [weak self] in
                let result = await self?.photo(url)
                guard let self, !Task.isCancelled else { return }
                self.plan.report(id, size: result?.source, now: Date())
                self.scheduleDeadline()
            }
        }
    }
    private func scheduleDeadline() {
        deadlineTask?.cancel()
        guard let deadline = plan.nextDeadline else { return }
        deadlineTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(max(0, deadline.timeIntervalSinceNow)))
            guard let self, !Task.isCancelled else { return }
            self.plan.tick(now: Date()); self.scheduleDeadline()
        }
    }
    override func close() {
        sizeTasks.values.forEach { $0.cancel() }; sizeTasks = [:]
        deadlineTask?.cancel(); form?.close(); form = nil; super.close()
    }
}

@MainActor @Observable
final class PurposeFormOwner: CategoryStateOwner<PurposeFormState> {
    @ObservationIgnored nonisolated(unsafe) let presenter: PurposeFormPresenter
    init(presenter: PurposeFormPresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }
}

extension SharedRuntime {
    func purposeUntilReady() async {
        for await value in ready where value.boolValue { return }
    }
}
