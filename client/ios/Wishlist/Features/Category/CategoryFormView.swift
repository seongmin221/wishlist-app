import Shared
import SwiftUI

/// The custom category form (FCategoryAddSheet, FCategoryEditSheet, FProductCategoryCreate) inside a sheet.
/// The Presenter owns the draft; the text fields keep a local copy (so typing never waits for the lane) and
/// re-read the Presenter's values when it replaces them (edit baseline loaded, a version conflict reloaded).
///
/// - Create (tab): parents (11 chips, SELECT), name, description, examples, the shared-category note, 취소·만들기.
/// - Create (picker): back + close header, "‘상위’ 아래에 …" note, the fields, "내 세부 카테고리 n / 20", 취소·만들기.
/// - Edit: "‘상위’ 아래 · 상품 n개", the fields, examples "n / 5", the rename note when it has items, 취소·저장.
/// While submitting every input is locked (closing too: the sheet guard blocks it).
struct CategoryFormView: View {
    let owner: CategoryFormOwner
    /// The user's close (X, 취소): goes through the sheet guard (asks when dirty).
    let onClose: () -> Void
    /// Picker create only: back to the chips (asks when dirty).
    var onBack: (() -> Void)?

    @Environment(\.wlColors) private var c
    @State private var name = ""
    @State private var description = ""
    @State private var pending = ""
    @State private var syncedSerial = -1

    private var state: CategoryFormState { owner.state }
    private var editing: Bool { state.mode == .edit }
    private var pickerCreate: Bool { !editing && state.source == .picker }
    private var locked: Bool { state.submitting || (editing && (state.phase == .loading || state.phase == .loadFailed)) }

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s20) {
            header
            if editing { editScope }
            if pickerCreate, let parent = state.parentName {
                note(DetailLine("category.form.picker.note", [.string(parent)]).resolve())
            }
            if !editing && !pickerCreate { parents }
            fields
            if !editing && !pickerCreate { note(String(localized: "category.form.create.note")) }
            if editing, let count = state.itemCount?.int32Value, count > 0 {
                note(DetailLine("category.form.rename.note", [.int(count)]).resolve())
            }
            if pickerCreate, let used = state.usedCount?.int32Value, let limit = state.limit?.int32Value {
                WLText(DetailLine("category.form.usage", [.int(used), .int(limit)]).resolve(), WLTextStyle.label.resized(13), color: c.textSecondary)
            }
            message
            buttons
        }
        .padding(.top, WishlistTokens.Space.s4)
        .onChange(of: syncKey, initial: true) { _, key in sync(key) }
    }

    // MARK: Parts

    private var header: some View {
        HStack(spacing: 8) {
            if let onBack {
                // Same face as the sheet's close button (a card-color circle is invisible on the sheet).
                Button(action: onBack) {
                    WLIcon(.back)
                        .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                        .background(c.sheetField, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "category.back"))
            }
            WLSheetHeader(title: String(localized: editing ? "category.form.edit.title" : "category.form.create.title"), onClose: onClose)
        }
    }

    private var editScope: some View {
        let parent = state.parentName ?? ""
        let count = state.itemCount?.int32Value ?? 0
        return WLText(DetailLine("category.form.edit.scope", [.string(parent), .int(count)]).resolve(),
                      WLTextStyle.label.resized(13), color: c.textSecondary)
    }

    @ViewBuilder private var parents: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(String(localized: "category.form.parent.label"), WLTextStyle.body.resized(13), color: c.textSecondary)
            if state.parents.isEmpty {
                // SELECT on its way: chip-shaped placeholders.
                WLFlowLayout {
                    ForEach(0..<6, id: \.self) { i in
                        Capsule().fill(c.sheetField).frame(width: [92, 120, 84, 104, 96, 132][i], height: 40)
                    }
                }
                .accessibilityLabel(String(localized: "category.loading"))
            } else {
                WLFlowLayout {
                    ForEach(state.parents, id: \.id) { parent in
                        WLChip(text: parent.name, selected: parent.id == state.parentId) {
                            owner.presenter.setParent(id: parent.id)
                        }
                        .disabled(locked)
                    }
                }
            }
            fieldError("parentId")
        }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s20) {
            VStack(alignment: .leading, spacing: 6) {
                WLInput(value: $name, label: String(localized: "category.form.name.label"), hint: String(localized: "category.form.name.hint"),
                        placeholder: String(localized: "category.form.name.placeholder"),
                        maxLength: Int(CategoryInputRules.shared.NAME_LIMIT), limitUnit: .codePoint)
                    .onChange(of: name) { _, text in owner.presenter.setName(text: text) }
                fieldError("name")
            }
            VStack(alignment: .leading, spacing: 6) {
                WLInput(value: $description, label: String(localized: "category.form.desc.label"), hint: String(localized: "category.form.desc.hint"),
                        placeholder: String(localized: "category.form.desc.placeholder"), singleLine: false, minLines: 2,
                        maxLength: Int(CategoryInputRules.shared.DESCRIPTION_LIMIT), limitUnit: .codePoint)
                    .onChange(of: description) { _, text in owner.presenter.setDescription(text: text) }
                fieldError("description")
            }
            examples
        }
        .disabled(locked)
        .redacted(reason: editing && state.phase == .loading ? .placeholder : [])
    }

    private var examples: some View {
        let count = state.examples.count
        let hint = editing ? DetailLine("category.form.examples.count", [.int(Int32(count))]).resolve() : String(localized: "category.form.examples.hint")
        return VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            if !state.examples.isEmpty {
                HStack {
                    WLText(String(localized: "category.form.examples.label"), WLTextStyle.body.resized(13), color: c.textSecondary)
                    Spacer(minLength: 0)
                    WLText(hint, .label, color: c.textSecondary)
                }
                WLFlowLayout {
                    ForEach(Array(state.examples.enumerated()), id: \.offset) { index, text in
                        ExampleChip(text: text) { owner.presenter.removeExample(index: Int32(index)) }
                    }
                }
            }
            if count < Int(CategoryInputRules.shared.EXAMPLE_COUNT_LIMIT) {
                WLInput(value: $pending,
                        label: state.examples.isEmpty ? String(localized: "category.form.examples.label") : nil,
                        hint: state.examples.isEmpty ? hint : nil,
                        placeholder: String(localized: "category.form.example.placeholder"),
                        maxLength: Int(CategoryInputRules.shared.EXAMPLE_LIMIT), limitUnit: .codePoint,
                        onSubmit: {
                            owner.presenter.setPendingExample(text: pending)
                            owner.presenter.commitExample()
                            pending = ""
                        })
                    .onChange(of: pending) { _, text in owner.presenter.setPendingExample(text: text) }
            }
            fieldError("examples")
        }
    }

    @ViewBuilder private func fieldError(_ field: String) -> some View {
        if let error = state.fieldErrors[field] {
            WLText(String(localized: String.LocalizationValue(CategoryText.fieldErrorKey(error))), WLTextStyle.label.resized(13),
                   color: WishlistTokens.Danger.surface)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }

    @ViewBuilder private var message: some View {
        if let message = state.message {
            HStack(spacing: WishlistTokens.Space.s8) {
                WLText(String(localized: String.LocalizationValue(CategoryText.messageKey(message))), WLTextStyle.label.resized(13),
                       color: message == .conflictLoaded ? c.textSecondary : WishlistTokens.Danger.surface)
                if CategoryText.messageRetries(message) {
                    Button { owner.presenter.retryLoad() } label: {
                        WLText(String(localized: "detail.retry"), WLTextStyle.bodyBold.resized(13), color: c.text)
                            .underline()
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    /// 취소 1 : 주 동작 1.4 (the `WLButtonPair` ratio); only the primary dims when it cannot submit.
    private var buttons: some View {
        GeometryReader { geo in
            let gap = WishlistTokens.Space.s12
            let unit = max(0, geo.size.width - gap) / 2.4
            HStack(spacing: gap) {
                WLButton(String(localized: "dialog.cancel"), kind: .secondary) {
                    if !state.submitting { (onBack ?? onClose)() }
                }
                .frame(width: unit)
                WLButton(String(localized: editing ? "category.form.save" : "category.form.create"), kind: .primary,
                         enabled: state.canSubmit || state.submitting) {
                    owner.presenter.submit()
                }
                .frame(width: unit * 1.4)
                .overlay(alignment: .trailing) {
                    if state.submitting { ProgressView().tint(c.onInverse).padding(.trailing, WishlistTokens.Space.s20) }
                }
            }
        }
        .frame(height: 52)
        .allowsHitTesting(!state.submitting)
    }

    private func note(_ text: String) -> some View {
        WLText(text, WLTextStyle.body.resized(13, lineHeight: 13 * 1.5), color: c.textSecondary)
    }

    // MARK: Syncing the local text with the Presenter's draft

    /// Bumps when the Presenter replaced the draft (edit baseline, conflict reload, form restart).
    private var syncKey: String {
        "\(state.mode)|\(state.phase)|\(state.message == .conflictLoaded ? "conflict" : "")|\(state.itemCount?.intValue ?? -1)"
    }

    private func sync(_ key: String) {
        guard state.phase == .ready || state.phase == .loading else { return }
        if name != state.name { name = state.name }
        if description != state.description_ { description = state.description_ }
        if pending != state.pendingExample { pending = state.pendingExample }
    }
}

/// An example chip with ×: sheet-field pill, 14 text, × 14 in a 28 hit box (board).
private struct ExampleChip: View {
    let text: String
    let onRemove: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: 4) {
            WLText(text, WLTextStyle.body.resized(14), color: c.text, maxLines: 1)
            Button(action: onRemove) {
                WLIcon(.close, size: 14)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "category.form.example.remove"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .frame(height: 36)
        .background(c.sheetField, in: Capsule())
    }
}

/// "변경 사항을 버릴까요?" (계속 편집 / 버리기); 버리기 closes the dialog and the sheet under it.
func categoryDiscardDialog(_ overlay: OverlayHostState, onDiscard: @escaping () -> Void = {}) -> WLDialogSpec {
    WLDialogSpec(
        title: String(localized: "category.form.discard.title"),
        bullets: [String(localized: "category.form.discard.body")],
        cancelText: String(localized: "category.form.discard.keep"),
        confirmText: String(localized: "category.form.discard.confirm"),
        confirmKind: .primary,
        onConfirm: {
            onDiscard()
        }
    )
}

/// The sheet guard for a form: submitting blocks, a dirty draft asks, otherwise it closes.
@MainActor
func categoryFormGuard(_ owner: CategoryFormOwner, overlay: OverlayHostState) -> SheetGuard {
    SheetGuard(
        decide: { [weak owner] in
            guard let state = owner?.state else { return .allow }
            if state.submitting { return .block }
            return state.dirty ? .confirm : .allow
        },
        onConfirm: { overlay.showDialog(categoryDiscardDialog(overlay) { overlay.dismissAll() }) }
    )
}
