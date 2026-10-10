import Shared
import SwiftUI

/// Owners per back-stack entry; ZStack keeps views alive, so lifetime follows the stack, not the view.
///
/// `WLNavHost` draws every entry of every tab in one `ZStack`, so a screen's `@State` lives as long as
/// its entry is drawn, which includes the exit motion after it left the stack. Screens therefore never
/// create their Presenter owner themselves: they ask this object by their entry id (`\.wlEntryID`).
/// The shell closes owners when the navigator reports the entry removed (`drainRemoved()` once the
/// transition finished, `dropAccountScoped()` at once).
///
/// A closed id is retired (navigator ids are never reused): asking for it again, e.g. from a screen
/// still drawn during its exit, returns a throwaway owner that is already closed, so the previous
/// account's Presenter never comes back.
@MainActor
final class WLEntryOwners {
    private let makeItem: () -> ItemDetailPresenter
    private let makeLocal: () -> LocalSubmissionDetailPresenter
    private var items: [Int: ItemDetailPresenterOwner] = [:]
    private var locals: [Int: LocalSubmissionPresenterOwner] = [:]
    private var retired: Set<Int> = []

    init(makeItem: @escaping () -> ItemDetailPresenter, makeLocal: @escaping () -> LocalSubmissionDetailPresenter) {
        self.makeItem = makeItem
        self.makeLocal = makeLocal
    }

    convenience init(runtime: SharedRuntime) {
        self.init(makeItem: { runtime.itemDetailPresenter() }, makeLocal: { runtime.localSubmissionDetailPresenter() })
    }

    func itemDetail(_ entryId: Int) -> ItemDetailPresenterOwner {
        if let owner = items[entryId] { return owner }
        let owner = ItemDetailPresenterOwner(presenter: makeItem())
        if retired.contains(entryId) {
            owner.close()
            return owner
        }
        items[entryId] = owner
        return owner
    }

    func localDetail(_ entryId: Int) -> LocalSubmissionPresenterOwner {
        if let owner = locals[entryId] { return owner }
        let owner = LocalSubmissionPresenterOwner(presenter: makeLocal())
        if retired.contains(entryId) {
            owner.close()
            return owner
        }
        locals[entryId] = owner
        return owner
    }

    /// close() and forget. Idempotent; ids without an owner are only retired.
    func close(_ ids: [Int]) {
        for id in ids {
            retired.insert(id)
            items.removeValue(forKey: id)?.close()
            locals.removeValue(forKey: id)?.close()
        }
    }
}

private struct WLEntryOwnersKey: EnvironmentKey {
    static let defaultValue: WLEntryOwners? = nil
}

private struct WLRuntimeKey: EnvironmentKey {
    static let defaultValue: SharedRuntime? = nil
}

extension EnvironmentValues {
    /// The app root's per-entry owners (`ContentView`). Screens read `wlEntryOwners`.
    var wlEntryOwnersStorage: WLEntryOwners? {
        get { self[WLEntryOwnersKey.self] }
        set { self[WLEntryOwnersKey.self] = newValue }
    }

    var wlEntryOwners: WLEntryOwners {
        guard let owners = self[WLEntryOwnersKey.self] else {
            preconditionFailure("Inject wlEntryOwners at the app root (ContentView).")
        }
        return owners
    }

    /// The process's single shared runtime, put in by `WishlistApp` for `ContentView`.
    var wlRuntime: SharedRuntime? {
        get { self[WLRuntimeKey.self] }
        set { self[WLRuntimeKey.self] = newValue }
    }
}
