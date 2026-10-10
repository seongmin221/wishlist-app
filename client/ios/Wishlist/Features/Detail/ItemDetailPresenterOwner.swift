import Observation
import Shared
import SwiftUI

/// Lifetime owner of one shared `ItemDetailPresenter` (no UI). It collects the Presenter's
/// thread-safe state in a main-actor task and republishes the concrete item/error/loading for
/// SwiftUI. `close()` or `deinit` cancels that collection and closes the Presenter; C4's detail
/// screen keeps this ownership.
///
/// The screen's per-entry facts live here, not in view state, so a recreated view (the navigator keeps
/// every entry drawn) neither loads again nor repeats a notice: `loadOnce`, `seenWork` (for
/// `shouldClose`), the announced error (`refreshNotices`) and the foreground transitions.
@MainActor
@Observable
final class ItemDetailPresenterOwner {
    private(set) var item: WishlistItem?
    private(set) var error: ClientError?
    private(set) var loading = false
    /// Bumped once per failed refresh while an item is shown; the screen shows the short notice on each change.
    private(set) var refreshNotices = 0
    /// A state other than Initial was seen (the first load started), so a later Initial means the account moved on.
    @ObservationIgnored private(set) var seenWork = false

    @ObservationIgnored private var started = false
    @ObservationIgnored private var noticedError: ClientError?
    @ObservationIgnored private var transitions: ForegroundTransitions = {
        var t = ForegroundTransitions()
        _ = t.isForeground(.active) // the screen opens while the app is in the foreground
        return t
    }()

    // Read from the nonisolated deinit; both are safe from any thread (Task / Kotlin close).
    @ObservationIgnored private nonisolated(unsafe) let presenter: ItemDetailPresenter
    @ObservationIgnored private nonisolated(unsafe) var collection: Task<Void, Never>?

    init(presenter: ItemDetailPresenter) {
        self.presenter = presenter
        let states = presenter.state
        // Weak self: the collection never keeps the owner alive, so deinit can end it.
        collection = Task { @MainActor [weak self] in
            for await state in states {
                guard let self else { return }
                self.apply(state)
            }
        }
    }

    /// A new Presenter from the process's single runtime (its one session, gated ITEM-03 facade).
    convenience init(runtime: SharedRuntime) {
        self.init(presenter: runtime.itemDetailPresenter())
    }

    func load(id: String) {
        presenter.load(id: id)
    }

    func retry() {
        presenter.retry()
    }

    /// The screen's first appearance loads; a recreated view asking again does not.
    func loadOnce(id: String) {
        if started { return }
        started = true
        load(id: id)
    }

    /// Pull to refresh and foreground returns; keeps the shown item. Nothing before the first load or after an account change.
    func refresh() {
        presenter.refresh()
    }

    /// For `.refreshable`: refreshes and returns once that refresh ended (Ruling 2: a state with
    /// loading = true, then loading = false). Returns at once when nothing is shown (no load yet, or
    /// the account moved on: the Presenter has nothing to repeat), and when the refresh never started
    /// within 2s (e.g. an account change raced it).
    func refreshAndWait() async {
        guard item != nil || error != nil else { return }
        let before = presenter.state.value
        presenter.refresh()
        var began = false
        for _ in 0..<40 {
            let now = presenter.state.value
            if now.loading || now != before {
                began = true
                break
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
            if Task.isCancelled { return }
        }
        guard began else { return }
        while presenter.state.value.loading, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    /// The scene's phase; a return from the background refreshes (C3 `ForegroundTransitions`). True when it refreshed.
    @discardableResult
    func scenePhaseChanged(_ phase: ScenePhase) -> Bool {
        guard transitions.isForeground(phase) else { return false }
        refresh()
        return true
    }

    /// The screen closes itself (Android `shouldClose`).
    var shouldClose: Bool { Self.shouldClose(item: item, loading: loading, error: error, seenWork: seenWork) }

    /// Initial after work started (the account generation changed), or signed out with nothing shown
    /// (a stack restored while signed out): the screen closes.
    nonisolated static func shouldClose(item: WishlistItem?, loading: Bool, error: ClientError?, seenWork: Bool) -> Bool {
        (seenWork && item == nil && !loading && error == nil) || (item == nil && !loading && error?.kind == .unauthenticated)
    }

    /// Whether this state carries a failed refresh not announced yet: an item is shown, the load ended
    /// with an error, and that error instance is new.
    func takeRefreshNotice(item: WishlistItem?, loading: Bool, error: ClientError?) -> Bool {
        guard item != nil, let error, !loading, error !== noticedError else { return false }
        noticedError = error
        return true
    }

    /// Idempotent: stops collecting and closes the Presenter; later intents are ignored.
    func close() {
        collection?.cancel()
        collection = nil
        presenter.close()
    }

    deinit {
        collection?.cancel()
        presenter.close()
    }

    private func apply(_ state: ItemDetailState) {
        if state.item != nil || state.loading || state.error != nil { seenWork = true }
        item = state.item
        error = state.error
        loading = state.loading
        if takeRefreshNotice(item: state.item, loading: state.loading, error: state.error) { refreshNotices += 1 }
    }
}
