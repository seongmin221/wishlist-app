import Observation
import Shared

/// Lifetime owner of the home tab's `HomePresenter` (the home tab root never leaves the stack, so
/// the app root owns it). The app-wide foreground refresh stays in `AppSignals`; C7 adds HOME-01 reads
/// on the home's own appearance and foreground (`HomeScreen`).
@MainActor
@Observable
final class HomePresenterOwner {
    private(set) var state: HomeState

    @ObservationIgnored private nonisolated(unsafe) let presenter: HomePresenter
    @ObservationIgnored private nonisolated(unsafe) var collection: Task<Void, Never>?

    init(presenter: HomePresenter) {
        self.presenter = presenter
        state = presenter.state.value
        let states = presenter.state
        collection = Task { @MainActor [weak self] in
            for await state in states {
                guard let self else { return }
                self.state = state
            }
        }
    }

    convenience init(runtime: SharedRuntime) {
        self.init(presenter: runtime.homePresenter())
    }

    /// Recomputes relative times; `HomeScreen` calls it every minute while it is shown.
    func tick() {
        presenter.tick()
    }

    /// The home became the visible top screen (first entry or a return): reads HOME-01.
    func appeared() { presenter.appeared() }

    /// The app came back while the home is visible: a coordinator refresh, then HOME-01.
    func foreground() { presenter.foreground() }

    /// HOME-01 again after a failure (the first load's retry).
    func retry() { presenter.retry() }

    /// Pull to refresh (`.refreshable`): returns once the Presenter's refresh finished (at once while
    /// another one runs or after close). Cancelling the pull ends only the wait (CancellationError).

    func refresh() async {
        try? await presenter.refreshNow()
    }

    func close() {
        collection?.cancel()
        collection = nil
        presenter.close()
    }

    deinit {
        collection?.cancel()
        presenter.close()
    }
}
