import Observation
import Shared

/// Lifetime owner of one shared `ItemDetailPresenter` (no UI). It collects the Presenter's
/// thread-safe state in a main-actor task and republishes the concrete item/error/loading for
/// SwiftUI. `close()` or `deinit` cancels that collection and closes the Presenter; C4's detail
/// screen keeps this ownership.
@MainActor
@Observable
final class ItemDetailPresenterOwner {
    private(set) var item: WishlistItem?
    private(set) var error: ClientError?
    private(set) var loading = false

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
        item = state.item
        error = state.error
        loading = state.loading
    }
}
