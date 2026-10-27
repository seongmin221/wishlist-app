import Observation
import Shared
import SwiftUI
import UIKit

/// Collects a Presenter's state flow on the main actor and republishes the latest value for SwiftUI
/// (the C4 owner shape). `close()` or `deinit` stops collecting and closes the Presenter; later intents
/// are ignored by the Presenter itself.
@MainActor
@Observable
class CategoryStateOwner<State: AnyObject> {
    private(set) var state: State

    // Read from the nonisolated deinit; both are safe from any thread (Task / Kotlin close).
    @ObservationIgnored private nonisolated(unsafe) let closePresenter: () -> Void
    @ObservationIgnored private nonisolated(unsafe) var collection: Task<Void, Never>?

    init(initial: State, states: SkieSwiftStateFlow<State>, close: @escaping () -> Void) {
        state = initial
        closePresenter = close
        collection = Task { @MainActor [weak self] in
            for await value in states {
                guard let self else { return }
                self.state = value
                self.applied(value)
            }
        }
    }

    /// Called on the main actor after each new state (subclasses keep their per-screen facts here).
    func applied(_ state: State) {}

    /// Idempotent.
    func close() {
        collection?.cancel()
        collection = nil
        closePresenter()
    }

    deinit {
        collection?.cancel()
        closePresenter()
    }
}

/// FCategoryHome's Presenter (one per category tab root entry).
@MainActor
@Observable
final class CategoryHomeOwner: CategoryStateOwner<CategoryHomeState> {
    @ObservationIgnored private nonisolated(unsafe) let presenter: CategoryHomePresenter
    @ObservationIgnored private var transitions: ForegroundTransitions = {
        var t = ForegroundTransitions()
        _ = t.isForeground(.active)
        return t
    }()
    @ObservationIgnored private var started = false
    /// D9: the group a create just added to; the pager moves there once the refreshed list has it.
    @ObservationIgnored var pendingGroup: String?
    /// Bumped once per failed refresh (rising edge); the screen shows the short notice on each change.
    private(set) var refreshNotice: BriefNotice?
    @ObservationIgnored private var refreshNoticeSerial = 0
    @ObservationIgnored private var refreshFailedShown = false

    init(presenter: CategoryHomePresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }

    /// The tab's first appearance loads; a recreated view does not.
    func loadOnce() {
        if started { return }
        started = true
        presenter.load()
    }

    func refresh() { presenter.refresh() }
    func retry() { presenter.retry() }
    func showGroup(_ id: String) { presenter.showGroup(groupId: id) }

    func refreshAndWait() async {
        try? await presenter.refreshNow()
    }

    override func applied(_ state: CategoryHomeState) {
        if state.refreshFailed && !refreshFailedShown {
            refreshNoticeSerial += 1
            refreshNotice = BriefNotice(serial: refreshNoticeSerial, text: String(localized: "detail.error.network"))
        }
        refreshFailedShown = state.refreshFailed
        if let group = pendingGroup, !state.loading, state.groups.contains(where: { $0.id == group }) {
            pendingGroup = nil
            showGroup(group)
        }
    }

    /// A return from the background refreshes (spec §3 갱신).
    func scenePhaseChanged(_ phase: ScenePhase) {
        if transitions.isForeground(phase) { refresh() }
    }
}

/// One category item list's Presenter (FCategoryList / ListCustom / ListCustomEmpty).
@MainActor
@Observable
final class CategoryListOwner: CategoryStateOwner<CategoryListState> {
    @ObservationIgnored private nonisolated(unsafe) let presenter: CategoryListPresenter
    @ObservationIgnored private var started = false

    init(presenter: CategoryListPresenter, loader: RemoteImageLoader = .shared) {
        self.presenter = presenter
        self.loader = loader
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }

    func loadOnce(_ target: CategoryListTarget) {
        if started { return }
        started = true
        presenter.load(hint: target.hint)
    }

    // MARK: D10 page layout (sizes only; images stay in the loader's cache and the drawn cards)

    private(set) var plan = PhotoPagePlan()
    /// Bumped once per pull-to-refresh whose header failed (rising edge of `partialFailed`).
    private(set) var partialNotice: BriefNotice?
    @ObservationIgnored private var partialShown = false
    @ObservationIgnored private var noticeSerial = 0
    @ObservationIgnored private var column: (width: CGFloat, scale: CGFloat)?
    @ObservationIgnored private var sizeTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var deadlineTask: Task<Void, Never>?
    @ObservationIgnored let loader: RemoteImageLoader
    @ObservationIgnored var now: () -> Date = Date.init

    /// The card photo request for the current column (the cards use the same one, so it is a cache hit).
    func photo(_ url: URL) async -> (image: UIImage, source: CGSize)? {
        guard let column else { return nil }
        return await loader.image(for: url, width: column.width, scale: column.scale,
                                  maxPixels: PhotoPagePlan.maxPixels(columnPixels: column.width * column.scale))
    }

    /// The list's column width (points) and display scale; sizes are read only once it is known.
    func setColumn(width: CGFloat, scale: CGFloat) {
        guard width > 0, column?.width != width || column?.scale != scale else { return }
        column = (width, scale)
        fetchSizes()
    }

    override func applied(_ state: CategoryListState) {
        // Built once per state: the masonry looks cards up by id in every body (a linear search there is O(n²)).
        itemsById = Dictionary(state.items.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let items = state.items.map { item in
            let url = remotePhotoURL(item.product.imageUrl)
            return PhotoPagePlan.Item(id: item.id, hasPhoto: url != nil, photo: url?.absoluteString)
        }
        if items != plan.knownItems {
            let before = plan.knownItems
            plan.sync(items, now: now())
            if !plan.knownItems.starts(with: before) {
                sizeTasks.values.forEach { $0.cancel() }
                sizeTasks = [:]
            }
            fetchSizes()
            scheduleDeadline()
        }
        if state.partialFailed && !partialShown {
            noticeSerial += 1
            partialNotice = BriefNotice(serial: noticeSerial, text: String(localized: "category.partial.failed"))
        }
        partialShown = state.partialFailed
    }

    /// The state's item by id (the index follows every applied state).
    func item(_ id: String) -> WishlistItem? { itemsById[id] }

    @ObservationIgnored private var itemsById: [String: WishlistItem] = [:]

    private func fetchSizes() {
        guard column != nil else { return }
        for id in plan.sizesNeeded where sizeTasks[id] == nil {
            guard let url = remotePhotoURL(item(id)?.product.imageUrl) else { continue }
            sizeTasks[id] = Task { @MainActor [weak self] in
                let result = await self?.photo(url)
                guard let self, !Task.isCancelled else { return }
                self.plan.report(id, size: result?.source, now: self.now())
                self.scheduleDeadline()
            }
        }
    }

    private func scheduleDeadline() {
        deadlineTask?.cancel()
        guard let deadline = plan.nextDeadline else { return }
        deadlineTask = Task { @MainActor [weak self] in
            let wait = max(0, deadline.timeIntervalSinceNow)
            try? await Task.sleep(for: .seconds(wait))
            guard let self, !Task.isCancelled else { return }
            self.plan.tick(now: self.now())
            self.scheduleDeadline()
        }
    }

    override func close() {
        sizeTasks.values.forEach { $0.cancel() }
        sizeTasks = [:]
        deadlineTask?.cancel()
        super.close()
    }

    func loadMore() { presenter.loadMore() }
    func retry() { presenter.retry() }
    func refreshHeader() { presenter.refreshHeader() }
    func applyEdited(_ detail: CustomCategoryDetail) { presenter.applyEdited(detail: detail) }
    func refreshAndWait() async { try? await presenter.refreshNow() }

    // MARK: C7 anchor window

    /// Pull to refresh around the first visible card (nil: the first page); sets `state.restore`.
    func refreshAndWait(anchor: String?) async { try? await presenter.refreshNow(anchorItemId: anchor) }
    /// A foreground return while the list is shown: the same window read, quietly.
    func foreground(anchor: String?) { presenter.foreground(anchorItemId: anchor) }
    /// The list reached its top edge with newer cards in front.
    func loadNewer() { presenter.loadNewer() }
    @ObservationIgnored var foregroundGate = FlowForegroundGate()
}

/// A create/edit form's Presenter, owned by the sheet that shows it (closed when the sheet closes).
@MainActor
@Observable
final class CategoryFormOwner: CategoryStateOwner<CategoryFormState> {
    @ObservationIgnored nonisolated(unsafe) let presenter: CategoryFormPresenter

    init(presenter: CategoryFormPresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }
}

/// The category picker sheet's Presenter, owned by the sheet.
@MainActor
@Observable
final class CategoryPickerOwner: CategoryStateOwner<CategoryPickerState> {
    @ObservationIgnored nonisolated(unsafe) let presenter: CategoryPickerPresenter

    init(presenter: CategoryPickerPresenter) {
        self.presenter = presenter
        super.init(initial: presenter.state.value, states: presenter.state, close: { presenter.close() })
    }
}

/// The runtime factories the category screens need (nil in owner tests that do not use them).
struct CategoryFactories {
    let home: () -> CategoryHomePresenter
    let list: () -> CategoryListPresenter
    let form: () -> CategoryFormPresenter
    let picker: () -> CategoryPickerPresenter

    init(runtime: SharedRuntime) {
        home = { runtime.categoryHomePresenter() }
        list = { runtime.categoryListPresenter() }
        form = { runtime.categoryFormPresenter() }
        picker = { runtime.categoryPickerPresenter() }
    }

    init(home: @escaping () -> CategoryHomePresenter, list: @escaping () -> CategoryListPresenter,
         form: @escaping () -> CategoryFormPresenter, picker: @escaping () -> CategoryPickerPresenter) {
        self.home = home
        self.list = list
        self.form = form
        self.picker = picker
    }
}
