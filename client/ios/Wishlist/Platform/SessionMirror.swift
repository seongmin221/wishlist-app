import Foundation

/// Mirrors the signed-in account into the app-group defaults (`wl.session.accountBinding`) so the
/// share extension, which does not link Shared, can bind a share to the account and pick its card
/// (SAVED_OPEN_APP vs LOCAL). Written on every account change once the login is restored; removed
/// while signed out. Not a credential: the extension never sends anything in C3.
struct SessionMirror {
    let defaults: UserDefaults?

    init(defaults: UserDefaults? = AppGroup.defaults()) {
        self.defaults = defaults
    }

    func update(_ binding: SessionBinding) {
        switch binding {
        case .unknown: break // restoring: keep what the last run mirrored
        case .signedOut: defaults?.removeObject(forKey: AppGroup.accountBindingKey)
        case .signedIn(let accountId): defaults?.set(accountId, forKey: AppGroup.accountBindingKey)
        }
    }
}
