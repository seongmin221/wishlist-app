import Observation
import Shared

/// Lifetime owner of the home tab's `HomePresenter` (the home tab root never leaves the stack, so
/// the app root owns it). Foreground refresh is app-wide (`AppSignals`), not here.
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

    /// Pull to refresh (`.refreshable`): returns once the Presenter's refresh finished (at once while
    /// another one runs or after close). Cancelling the pull ends only the wait (CancellationError).
    /// Recomputes relative times; `HomeScreen` calls it every minute while it is shown.
    func tick() {
        presenter.tick()
    }

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
