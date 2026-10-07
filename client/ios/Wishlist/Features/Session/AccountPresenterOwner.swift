import Observation
import Shared

/// What the share extension should know about the session (mirrored by `SessionMirror`).
enum SessionBinding: Equatable {
    /// The login is still being restored: nothing is known yet.
    case unknown
    case signedOut
    case signedIn(String)

    init(_ state: AccountState) {
        if !state.restored {
            self = .unknown
        } else if let account = state.account {
            self = .signedIn(account.accountId)
        } else {
            self = .signedOut
        }
    }
}

/// Lifetime owner of the app's one `AccountPresenter` (no UI): the first-run login layer, the home
/// login card, the login screen and settings all read this state. It collects the Presenter's
/// thread-safe state in a main-actor task and republishes it for SwiftUI; `close()` or `deinit`
/// ends the collection and closes the Presenter (same shape as `ItemDetailPresenterOwner`).
/// Failures (`state.error`) have no persistent UI in C3: RELEASE sign-in fails silently until the
/// real auth connection.
@MainActor
@Observable
final class AccountPresenterOwner {
    private(set) var state: AccountState

    // Read from the nonisolated deinit; both are safe from any thread (Task / Kotlin close).
    @ObservationIgnored private nonisolated(unsafe) let presenter: AccountPresenter
    @ObservationIgnored private nonisolated(unsafe) var collection: Task<Void, Never>?

    init(presenter: AccountPresenter) {
        self.presenter = presenter
        state = presenter.state.value
        let states = presenter.state
        // Weak self: the collection never keeps the owner alive, so deinit can end it.
        collection = Task { @MainActor [weak self] in
            for await state in states {
                guard let self else { return }
                self.state = state
            }
        }
    }

    /// A new Presenter from the process's single runtime.
    convenience init(runtime: SharedRuntime) {
        self.init(presenter: runtime.accountPresenter())
    }

    var binding: SessionBinding { SessionBinding(state) }

    /// Sign-in and sign-out controls are disabled while a sign-in runs.
    var idle: Bool { state.signingIn == nil }

    func signIn(_ provider: AuthProvider) {
        presenter.signIn(provider: provider)
    }

    func skipFirstRunLogin() {
        presenter.skipFirstRunLogin()
    }

    func signOut() {
        presenter.signOut()
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
}
