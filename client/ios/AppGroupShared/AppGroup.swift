import Foundation

/// The app group shared by the app and the share extension (C3-D1). Both sides take the inbox
/// directory and the defaults as injected values: a build without entitlements (IOS_TEST uses
/// `CODE_SIGNING_ALLOWED=NO`) has no group container, and then only the inbox is disabled.
enum AppGroup {
    static let identifier = "group.app.wishlist"
    /// Signed-in account id mirrored by the app (`SessionMirror`); absent while signed out.
    /// The extension reads it to bind a share and choose its card (C3-D9).
    static let accountBindingKey = "wl.session.accountBinding"
    static let inboxFolder = "inbox"

    /// nil when the binary carries no app-group entitlement (unsigned builds).
    static func containerURL(_ fileManager: FileManager = .default) -> URL? {
        fileManager.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    /// `<container>/inbox/`, or nil without a container.
    static func inboxDirectory(_ fileManager: FileManager = .default) -> URL? {
        containerURL(fileManager)?.appendingPathComponent(inboxFolder, isDirectory: true)
    }

    /// The group's defaults. Without the entitlement iOS still returns a (process-private) suite,
    /// so callers that need sharing also check `containerURL`.
    static func defaults() -> UserDefaults? {
        UserDefaults(suiteName: identifier)
    }
}
