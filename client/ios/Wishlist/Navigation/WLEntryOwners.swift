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
/// still drawn during its exit, returns one shared owner per kind that is already closed, so the previous
/// account's Presenter never comes back and no Presenter is built per request.
@MainActor
final class WLEntryOwners {
    private let makeItem: () -> ItemDetailPresenter
    private let makeLocal: () -> LocalSubmissionDetailPresenter
    private let makeWeb: @MainActor (URL) -> WebViewModel
    private var items: [Int: ItemDetailPresenterOwner] = [:]
    private var locals: [Int: LocalSubmissionPresenterOwner] = [:]
    private var webs: [Int: WebViewModel] = [:]
    private var categoryHomes: [Int: CategoryHomeOwner] = [:]
    private var categoryLists: [Int: CategoryListOwner] = [:]
    /// C5 category Presenters; nil only in owner tests that never open a category screen.
    let categories: CategoryFactories?
    private var purposeHomes: [Int: PurposeHomeOwner] = [:]
    private var purposeDetails: [Int: PurposeDetailOwner] = [:]
    let purposes: PurposeFactories?
    /// C7 continuous-processing flows; nil only in owner tests that never open one.
    private let makeFlow: (() -> HomeFlowPresenter)?
    private var flows: [Int: HomeFlowOwner] = [:]
    private var retired: Set<Int> = []
    private lazy var closedItem: ItemDetailPresenterOwner = {
        let owner = ItemDetailPresenterOwner(presenter: makeItem())
        owner.close()
        return owner
    }()
    private lazy var closedLocal: LocalSubmissionPresenterOwner = {
        let owner = LocalSubmissionPresenterOwner(presenter: makeLocal())
        owner.close()
        return owner
    }()

    init(
        makeItem: @escaping () -> ItemDetailPresenter,
        makeLocal: @escaping () -> LocalSubmissionDetailPresenter,
        makeWeb: @escaping @MainActor (URL) -> WebViewModel = { @MainActor in WebViewModel(url: $0) },
        categories: CategoryFactories? = nil,
        purposes: PurposeFactories? = nil,
        makeFlow: (() -> HomeFlowPresenter)? = nil
    ) {
        self.makeItem = makeItem
        self.makeLocal = makeLocal
        self.makeWeb = makeWeb
        self.categories = categories
        self.purposes = purposes
        self.makeFlow = makeFlow
    }

    convenience init(runtime: SharedRuntime) {
        self.init(makeItem: { runtime.itemDetailPresenter() }, makeLocal: { runtime.localSubmissionDetailPresenter() },
                  categories: CategoryFactories(runtime: runtime), purposes: PurposeFactories(runtime: runtime),
                  makeFlow: { runtime.homeFlowPresenter() })
    }

    private var factories: CategoryFactories {
        guard let categories else { preconditionFailure("WLEntryOwners built without category factories") }
        return categories
    }

    private var purposeFactories: PurposeFactories {
        guard let purposes else { preconditionFailure("WLEntryOwners built without purpose factories") }
        return purposes
    }

    func purposeHome(_ id: Int) -> PurposeHomeOwner {
        if let owner = purposeHomes[id] { return owner }
        let owner = PurposeHomeOwner(presenter: purposeFactories.home())
        if retired.contains(id) { owner.close(); return owner }
        purposeHomes[id] = owner
        return owner
    }

    func purposeDetail(_ id: Int) -> PurposeDetailOwner {
        if let owner = purposeDetails[id] { return owner }
        let owner = PurposeDetailOwner(presenter: purposeFactories.detail())
        if retired.contains(id) { owner.close(); return owner }
        purposeDetails[id] = owner
        return owner
    }

    /// A home flow entry's owner (C7); closed with the entry like the detail owners.
    func homeFlow(_ id: Int) -> HomeFlowOwner {
        if let owner = flows[id] { return owner }
        guard let makeFlow else { preconditionFailure("WLEntryOwners built without a flow factory") }
        let owner = HomeFlowOwner(presenter: makeFlow())
        if retired.contains(id) { owner.close(); return owner }
        flows[id] = owner
        return owner
    }

    func purposeForm() -> PurposeFormOwner { PurposeFormOwner(presenter: purposeFactories.form()) }

    /// The category tab root's owner (the root entry is never removed, so it lives with the app).
    func categoryHome(_ entryId: Int) -> CategoryHomeOwner {
        if let owner = categoryHomes[entryId] { return owner }
        let owner = CategoryHomeOwner(presenter: factories.home())
        if retired.contains(entryId) { owner.close(); return owner }
        categoryHomes[entryId] = owner
        return owner
    }

    /// A category list entry's owner; closed with the entry like the detail owners.
    func categoryList(_ entryId: Int) -> CategoryListOwner {
        if let owner = categoryLists[entryId] { return owner }
        let owner = CategoryListOwner(presenter: factories.list())
        if retired.contains(entryId) { owner.close(); return owner }
        categoryLists[entryId] = owner
        return owner
    }

    /// A new form owner for one sheet; the sheet closes it when it goes away.
    func categoryForm() -> CategoryFormOwner { CategoryFormOwner(presenter: factories.form()) }

    /// A new picker owner for one sheet; the sheet closes it when it goes away.
    func categoryPicker() -> CategoryPickerOwner { CategoryPickerOwner(presenter: factories.picker()) }

    func itemDetail(_ entryId: Int) -> ItemDetailPresenterOwner {
        if let owner = items[entryId] { return owner }
        if retired.contains(entryId) { return closedItem }
        let owner = ItemDetailPresenterOwner(presenter: makeItem())
        items[entryId] = owner
        return owner
    }

    func localDetail(_ entryId: Int) -> LocalSubmissionPresenterOwner {
        if let owner = locals[entryId] { return owner }
        if retired.contains(entryId) { return closedLocal }
        let owner = LocalSubmissionPresenterOwner(presenter: makeLocal())
        locals[entryId] = owner
        return owner
    }

    /// The FWebView entry's web view owner (`AppDestination.web`), built with the route URL on first request.
    func web(_ entryId: Int, url: URL) -> WebViewModel {
        if let owner = webs[entryId] { return owner }
        let owner = makeWeb(url)
        if retired.contains(entryId) {
            owner.close()
            return owner
        }
        webs[entryId] = owner
        return owner
    }

    /// close() and forget. Idempotent; ids without an owner are only retired.
    func close(_ ids: [Int]) {
        for id in ids {
            retired.insert(id)
            items.removeValue(forKey: id)?.close()
            locals.removeValue(forKey: id)?.close()
            webs.removeValue(forKey: id)?.close()
            categoryHomes.removeValue(forKey: id)?.close()
            categoryLists.removeValue(forKey: id)?.close()
            purposeHomes.removeValue(forKey: id)?.close()
            purposeDetails.removeValue(forKey: id)?.close()
            flows.removeValue(forKey: id)?.close()
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
