import Observation
import Shared

/// Lifetime owner of one shared `LocalSubmissionDetailPresenter` (no UI), the same shape as
/// `ItemDetailPresenterOwner`: it collects the Presenter's thread-safe state in a main-actor task and
/// republishes its fields for SwiftUI. `close()` or `deinit` cancels that collection and closes the
/// Presenter; later intents are ignored. Like the item owner it keeps the screen's per-entry facts
/// (`loadOnce`, the announced delete failure) so a recreated view does not repeat them.
@MainActor
@Observable
final class LocalSubmissionPresenterOwner {
    private(set) var row: LocalDetailRow?
    private(set) var canDelete = false
    private(set) var deleting = false
    private(set) var deleteFailed = false
    private(set) var outcome: LocalDetailOutcome?
    /// Bumped once per failed delete (rising edge of `deleteFailed`); the screen shows "지우지 못했어요" on each change.
    private(set) var deleteFailureNotices = 0
    /// The id the first `loadOnce` asked for.
    @ObservationIgnored private(set) var requestedId: String?
    @ObservationIgnored private var deleteFailureShown = false

    // Read from the nonisolated deinit; both are safe from any thread (Task / Kotlin close).
    @ObservationIgnored private nonisolated(unsafe) let presenter: LocalSubmissionDetailPresenter
    @ObservationIgnored private nonisolated(unsafe) var collection: Task<Void, Never>?

    init(presenter: LocalSubmissionDetailPresenter) {
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

    /// A new Presenter from the process's single runtime (its one coordinator view and session).
    convenience init(runtime: SharedRuntime) {
        self.init(presenter: runtime.localSubmissionDetailPresenter())
    }

    func load(submissionId: String) {
        presenter.load(submissionId: submissionId)
    }

    /// The screen's first appearance loads; a recreated view asking again does not.
    func loadOnce(submissionId: String) {
        if requestedId != nil { return }
        requestedId = submissionId
        load(submissionId: submissionId)
    }

    /// Whether a delete failure is new: true once per rising edge; the next view clears the flag and re-arms it.
    func takeDeleteFailureNotice(deleteFailed: Bool) -> Bool {
        guard deleteFailed else {
            deleteFailureShown = false
            return false
        }
        if deleteFailureShown { return false }
        deleteFailureShown = true
        return true
    }

    func delete() {
        presenter.delete()
    }

    /// Recomputes the relative saved time; the screen calls it every minute while shown.
    func tick() {
        presenter.tick()
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

    private func apply(_ state: LocalDetailState) {
        row = state.row
        canDelete = state.canDelete
        deleting = state.deleting
        deleteFailed = state.deleteFailed
        outcome = state.outcome
        if takeDeleteFailureNotice(deleteFailed: state.deleteFailed) { deleteFailureNotices += 1 }
    }
}
