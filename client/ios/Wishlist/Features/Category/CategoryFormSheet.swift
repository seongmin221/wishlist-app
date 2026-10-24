import Shared
import SwiftUI

/// Opens a create/edit form in a guarded sheet; 취소 · 만들기/저장 are the sheet's pinned footer. The form owner
/// lives exactly as long as the sheet: it is closed when the sheet's content goes away (any close path).
/// `onResult` runs once per result (Created / Saved / Gone) after the sheet started closing.
/// `beforeSubmit` is a DEBUG hook that runs right before each submit (nil in RELEASE).
@MainActor
func showCategoryFormSheet(
    overlay: OverlayHostState,
    owners: WLEntryOwners,
    start: @escaping (CategoryFormPresenter) -> Void,
    beforeSubmit: (() async -> Void)? = nil,
    onResult: @escaping (FormResult) -> Void
) {
    let owner = owners.categoryForm()
    start(owner.presenter)
    overlay.showSheet(title: String(localized: "category.form.create.title"), guard: categoryFormGuard(owner, overlay: overlay)) {
        CategoryFormSheetContent(owner: owner, onResult: onResult)
    } footer: {
        CategoryFormSheetFooter(owner: owner, beforeSubmit: beforeSubmit)
    }
}

private struct CategoryFormSheetFooter: View {
    let owner: CategoryFormOwner
    let beforeSubmit: (() async -> Void)?

    @Environment(\.overlayHostState) private var overlay

    var body: some View {
        CategoryFormButtons(owner: owner, onCancel: { overlay?.userDismiss() }, beforeSubmit: beforeSubmit)
    }
}

private struct CategoryFormSheetContent: View {
    let owner: CategoryFormOwner
    let onResult: (FormResult) -> Void

    @Environment(\.overlayHostState) private var overlay

    var body: some View {
        CategoryFormView(owner: owner, onClose: { overlay?.userDismiss() })
            .onChange(of: owner.state.result == nil, initial: true) { _, none in
                guard !none, let result = owner.state.result else { return }
                owner.presenter.consumeResult()
                overlay?.dismissAll()
                onResult(result)
            }
            .onDisappear { owner.close() }
    }
}
