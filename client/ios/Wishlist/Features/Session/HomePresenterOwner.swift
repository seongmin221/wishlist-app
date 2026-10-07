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

    /// Pull to refresh (`.refreshable`): asks the Presenter, then returns once its `refreshing`
    /// went back to false — or at once if the refresh finished before it was ever seen (the
    /// Presenter publishes a conflated StateFlow, so a fast refresh can skip the `true` frame).
    func refresh() async {
        presenter.refresh()
        var sawRefreshing = false
        let start = ContinuousClock.now
        while !Task.isCancelled {
            guard let loggedIn = presenter.state.value as? HomeStateLoggedIn else { return }
            if loggedIn.refreshing {
                sawRefreshing = true
            } else if sawRefreshing || ContinuousClock.now - start > .milliseconds(300) {
                return
            }
            if ContinuousClock.now - start > .seconds(30) { return }
            try? await Task.sleep(for: .milliseconds(30))
        }
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
