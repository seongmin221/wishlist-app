import Observation
import Shared
import SwiftUI

/// The picker sheet's two pages and their owners (the sheet guard reads it).
@MainActor
@Observable
final class CategoryPickerSheetModel {
    let picker: CategoryPickerOwner
    private(set) var form: CategoryFormOwner?
    @ObservationIgnored private let makeForm: () -> CategoryFormOwner

    init(picker: CategoryPickerOwner, makeForm: @escaping () -> CategoryFormOwner) {
        self.picker = picker
        self.makeForm = makeForm
    }

    /// `+ 카테고리`: the same sheet becomes the create form under the shown group (SELECT handed over).
    func startCreate() {
        let owner = makeForm()
        let state = picker.state
        owner.presenter.startCreate(parentId: state.currentGroupId, source: .picker, select: state.catalog)
        form = owner
    }

    /// Back to the chips (the form's draft is dropped).
    func endCreate() {
        form?.close()
        form = nil
    }

    func close() {
        endCreate()
        picker.close()
    }

    /// Submitting blocks a close; a dirty form asks; otherwise the sheet closes.
    func decide() -> SheetDismiss {
        guard let state = form?.state else { return .allow }
        if state.submitting { return .block }
        return state.dirty ? .confirm : .allow
    }
}

/// Opens FProductCategoryPicker. `onPick` gets the chosen (or just created, D14) category after the sheet started closing.
@MainActor
func showCategoryPicker(overlay: OverlayHostState, owners: WLEntryOwners, selectedId: String?, onPick: @escaping (CategoryEntry) -> Void) {
    let model = CategoryPickerSheetModel(picker: owners.categoryPicker(), makeForm: { owners.categoryForm() })
    model.picker.presenter.open(selectedId: selectedId)
    let sheetGuard = SheetGuard(
        decide: { [weak model] in model?.decide() ?? .allow },
        onConfirm: { overlay.showDialog(categoryDiscardDialog(overlay) { overlay.dismissAll() }) }
    )
    overlay.showSheet(title: String(localized: "category.picker.title"), guard: sheetGuard) {
        CategoryPickerSheetContent(model: model, onPick: onPick)
    }
}

private struct CategoryPickerSheetContent: View {
    let model: CategoryPickerSheetModel
    let onPick: (CategoryEntry) -> Void

    @Environment(\.overlayHostState) private var overlay
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let switching = reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll)
        ZStack {
            if let form = model.form {
                CategoryFormView(owner: form, onClose: { overlay?.userDismiss() }, onBack: { back(form) })
                    .transition(.opacity)
                    .onChange(of: form.state.result == nil) { _, none in
                        guard !none, let result = form.state.result as? FormResultCreated else { return }
                        form.presenter.consumeResult()
                        model.picker.presenter.onCreated(detail: result.detail) // D14: it becomes the pick
                    }
            } else {
                CategoryPickerPage(owner: model.picker, onClose: { overlay?.userDismiss() }, onCreate: {
                    withAnimation(switching) { model.startCreate() }
                })
                .transition(.opacity)
            }
        }
        .animation(switching, value: model.form == nil)
        .onChange(of: model.picker.state.result == nil, initial: true) { _, none in
            guard !none, let pick = model.picker.state.result else { return }
            model.picker.presenter.consumeResult()
            overlay?.dismissAll()
            onPick(pick)
        }
        .onDisappear { model.close() }
    }

    private func back(_ form: CategoryFormOwner) {
        guard let overlay else { return }
        if form.state.submitting { return }
        if form.state.dirty {
            overlay.showDialog(categoryDiscardDialog(overlay) { model.endCreate() })
        } else {
            model.endCreate()
        }
    }
}

/// "카테고리 선택": rail 120 + the group's chips (the selection is a filled ink chip, no check) and `+ 카테고리`.
private struct CategoryPickerPage: View {
    let owner: CategoryPickerOwner
    let onClose: () -> Void
    let onCreate: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        let state = owner.state
        VStack(alignment: .leading, spacing: 0) {
            WLSheetHeader(title: String(localized: "category.picker.title"), onClose: onClose)
                .padding(.bottom, WishlistTokens.Space.s16)
            Rectangle().fill(c.line).frame(height: 1).padding(.horizontal, -WishlistTokens.Space.screenMargin)
            if let catalog = state.catalog {
                HStack(alignment: .top, spacing: 0) {
                    rail(catalog, current: state.currentGroupId)
                    chips(catalog, state: state)
                }
                .padding(.leading, -WishlistTokens.Space.screenMargin)
            } else if let error = state.error {
                DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "detail.retry")) {
                    owner.presenter.retry()
                }
                .padding(.vertical, WishlistTokens.Space.s32)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.vertical, 120)
            }
        }
    }

    private func rail(_ catalog: CategoryCatalog, current: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(catalog.groups, id: \.id) { group in
                let on = group.id == current
                Button { owner.presenter.showGroup(groupId: group.id) } label: {
                    HStack(spacing: 0) {
                        Rectangle().fill(on ? c.text : Color.clear).frame(width: 3)
                        WLText(CategoryText.railLabel(group.name), (on ? WLTextStyle.bodyBold : WLTextStyle.body).resized(15, lineHeight: 15 * 1.3),
                               color: on ? c.text : c.textSecondary)
                            .padding(.leading, 17)
                            .padding(.trailing, 8)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(group.name)
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(.top, 6)
        .frame(width: 120)
    }

    private func chips(_ catalog: CategoryCatalog, state: CategoryPickerState) -> some View {
        let group = catalog.groups.first { $0.id == state.currentGroupId } ?? catalog.groups.first
        return VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(group?.name ?? "", WLTextStyle.label.resized(13), color: c.textSecondary, maxLines: 1)
                .frame(minHeight: 44, alignment: .leading)
            WLFlowLayout {
                ForEach(group?.categories ?? [], id: \.id) { entry in
                    WLChip(text: entry.name, selected: entry.id == state.selectedId) { owner.presenter.select(id: entry.id) }
                }
                WLAddChip(text: String(localized: "category.picker.add.chip"), action: onCreate)
            }
        }
        .padding(.top, 8)
        .padding(.leading, WishlistTokens.Space.s8)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}
