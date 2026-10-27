import Shared
import SwiftUI

@MainActor
func showPurposeCreateSheet(overlay: OverlayHostState, owners: WLEntryOwners, onResult: @escaping (PurposeDetail) -> Void) {
    let owner = owners.purposeForm()
    owner.presenter.startCreate()
    let guardRule = SheetGuard(decide: { [weak owner] in
        guard let state = owner?.state else { return .allow }
        switch PurposePresentation.dismiss(submitting: state.submitting, dirty: state.dirty) {
        case .allow: return .allow
        case .block: return .block
        case .confirm: return .confirm
        }
    }, onConfirm: {
        overlay.showDialog(categoryDiscardDialog(overlay) { overlay.dismissAll() })
    })
    overlay.showSheet(title: String(localized: "purpose.form.title"), guard: guardRule) {
        PurposeCreateContent(owner: owner, onResult: onResult)
    } footer: {
        PurposeFormButtons(owner: owner) { overlay.userDismiss() }
    }
}

private struct PurposeCreateContent: View {
    let owner: PurposeFormOwner
    let onResult: (PurposeDetail) -> Void
    @Environment(\.overlayHostState) private var overlay
    @State private var handled = false
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            WLSheetHeader(title: String(localized: "purpose.form.title")) { overlay?.userDismiss() }
            PurposeFields(owner: owner)
            PurposePalette(owner: owner, inset: false)
            WLText(String(localized: "purpose.form.note"), WLTextStyle.body.resized(13))
            PurposeFormMessage(owner: owner)
        }
        .onChange(of: owner.state.result, initial: true) { _, result in
            guard let result, !handled else { return }
            handled = true
            overlay?.dismissAll()
            onResult(result)
        }
        .onDisappear { owner.close() }
    }
}

/// Both create fields and the in-place header retain local IME composition while intents enter the lane.
struct PurposeFields: View {
    let owner: PurposeFormOwner
    var inPlace = false
    @State private var name = ""
    @State private var description = ""
    @Environment(\.wlColors) private var c
    var body: some View {
        VStack(alignment: .leading, spacing: inPlace ? 10 : 20) {
            WLInput(value: $name, label: inPlace ? nil : String(localized: "purpose.form.name.label"), placeholder: String(localized: "purpose.form.name.placeholder"), maxLength: 40, limitUnit: .codePoint)
                .onChange(of: name) { _, value in owner.presenter.changeName(value: value) }
            WLInput(value: $description, label: inPlace ? nil : String(localized: "purpose.form.desc.label"), placeholder: String(localized: "purpose.form.desc.placeholder"), singleLine: false,
                    minLines: inPlace ? 1 : 2, maxLength: 200, limitUnit: .codePoint)
                .onChange(of: description) { _, value in owner.presenter.changeDescription(value: value) }
            ForEach(owner.state.fieldErrors.keys.sorted(), id: \.self) { key in
                if let error = owner.state.fieldErrors[key] {
                    WLText(String(localized: String.LocalizationValue(CategoryText.fieldErrorKey(error))), .label, color: WishlistTokens.Danger.surface)
                }
            }
        }
        .disabled(owner.state.submitting)
        .onAppear { copyDraft() }
        .onChange(of: owner.state.message) { _, message in
            if message == .conflictLoaded { copyDraft() }
        }
    }
    private func copyDraft() { name = owner.state.name; description = owner.state.purposeDescription }
}

struct PurposePalette: View {
    let owner: PurposeFormOwner
    var inset = true
    @Environment(\.colorScheme) private var scheme
    @Environment(\.wlColors) private var c
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            WLText(String(localized: "purpose.form.color"), WLTextStyle.label.resized(13), color: c.textSecondary)
            WLFlowLayout(spacing: 4) {
                ForEach(PurposeStyle.colors, id: \.self) { key in
                    Button { owner.presenter.chooseColor(key: key) } label: {
                        Circle().fill(PurposeStyle.color(key))
                            .overlay {
                                if scheme != .dark, let color = DetailText.purposeColor(key) {
                                    Circle().strokeBorder(color.lightBorder, lineWidth: 1)
                                }
                            }
                            .padding(4)
                            .overlay(Circle().strokeBorder(owner.state.colorKey == key ? c.text : .clear, lineWidth: 2))
                            .frame(width: 48, height: 48)
                    }.buttonStyle(.plain).accessibilityLabel(key)
                        .accessibilityAddTraits(owner.state.colorKey == key ? .isSelected : [])
                }
            }
            WLText(String(localized: "purpose.form.icon"), WLTextStyle.label.resized(13), color: c.textSecondary)
            WLFlowLayout {
                ForEach(PurposeStyle.icons, id: \.self) { key in
                    let selected = owner.state.iconKey == key
                    Button { owner.presenter.chooseIcon(key: key) } label: {
                        WLIcon(PurposeStyle.icon(key), size: 20, color: selected ? c.onInverse : c.text)
                            .frame(width: 44, height: 44)
                            .background(selected ? c.text : c.sheetField, in: RoundedRectangle(cornerRadius: 15))
                    }.buttonStyle(.plain).accessibilityLabel(key)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .padding(inset ? 16 : 0)
        .background(inset ? c.card : c.sheet, in: RoundedRectangle(cornerRadius: 28))
        .disabled(owner.state.submitting)
    }
}

struct PurposeFormButtons: View {
    let owner: PurposeFormOwner
    var inPlace = false
    @Environment(\.wlColors) private var c
    let onCancel: () -> Void
    @ViewBuilder var body: some View {
        if inPlace {
            HStack(spacing: 8) {
                headerButton(String(localized: "purpose.cancel"), background: c.sheet, foreground: c.text, enabled: !owner.state.submitting, action: onCancel)
                if owner.state.submitting {
                    ProgressView().frame(width: 74, height: 44).background(c.text, in: Capsule()).tint(c.onInverse)
                } else {
                    headerButton(String(localized: "purpose.save"), background: c.text, foreground: c.onInverse, enabled: owner.state.canSubmit) { owner.presenter.submit() }
                }
            }
        } else {
        GeometryReader { geometry in
            let unit = max(0, geometry.size.width - 12) / 2.4
            HStack(spacing: 12) {
                WLButton(String(localized: "purpose.cancel"), kind: .secondary) { if !owner.state.submitting { onCancel() } }
                    .frame(width: unit).disabled(owner.state.submitting)
                if owner.state.submitting {
                    ProgressView().frame(width: unit * 1.4, height: 52).accessibilityLabel(String(localized: "purpose.saving"))
                } else {
                    WLButton(owner.state.mode == .edit ? String(localized: "purpose.save") : String(localized: "purpose.create"), kind: .primary, enabled: owner.state.canSubmit) { owner.presenter.submit() }
                        .frame(width: unit * 1.4)
                }
            }
        }.frame(height: 52)
        }
    }
    private func headerButton(_ title: String, background: Color, foreground: Color, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            WLText(title, WLTextStyle.button.resized(14), color: foreground)
                .padding(.horizontal, 22).frame(height: 44).background(background, in: Capsule())
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.4)
    }
}

struct PurposeFormMessage: View {
    let owner: PurposeFormOwner
    @Environment(\.wlColors) private var c
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if owner.state.mode == .edit {
                ForEach(owner.state.fieldErrors.keys.sorted(), id: \.self) { key in
                    if let error = owner.state.fieldErrors[key] {
                        WLText(String(localized: String.LocalizationValue(CategoryText.fieldErrorKey(error))), .label, color: WishlistTokens.Danger.surface)
                    }
                }
            }
            if let message = owner.state.message {
                HStack {
                    WLText(message == .limitReached ? String(localized: "purpose.form.limit") : String(localized: String.LocalizationValue(CategoryText.messageKey(message))), .label,
                           color: message == .conflictLoaded ? c.textSecondary : WishlistTokens.Danger.surface)
                    if CategoryText.messageRetries(message) {
                        Button(String(localized: "purpose.retry")) { owner.presenter.retryLoad() }
                    }
                }
            }
        }
    }
}
