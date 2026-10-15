import Shared
import SwiftUI

/// The form's 취소 · 만들기/저장, pinned at the sheet's bottom (board: sheet footer above the home indicator,
/// above the keyboard while it is up). 취소 1 : 주 동작 1.4 (the `WLButtonPair` ratio); only the primary dims
/// when it cannot submit. While submitting (and during a 409 reload) the primary shows only a progress view and
/// takes no taps; 취소 looks the same but does nothing until the answer (the sheet guard also blocks closing).
/// `beforeSubmit` (DEBUG hook) runs right before the submit.
struct CategoryFormButtons: View {
    let owner: CategoryFormOwner
    let onCancel: () -> Void
    var beforeSubmit: (() async -> Void)?

    @Environment(\.wlColors) private var c
    @State private var preparing = false

    var body: some View {
        let state = owner.state
        let busy = state.submitting || preparing
        GeometryReader { geo in
            let gap = WishlistTokens.Space.s12
            let unit = max(0, geo.size.width - gap) / 2.4
            HStack(spacing: gap) {
                WLButton(String(localized: "dialog.cancel"), kind: .secondary) {
                    if !busy { onCancel() }
                }
                .frame(width: unit)
                if busy {
                    // User decision 2026-10-15: while waiting (submit, or the 409 reload) the label's place shows
                    // a small progress view and the button takes no taps.
                    ProgressView()
                        .tint(c.onInverse)
                        .frame(width: unit * 1.4, height: 52)
                        .background(c.text, in: Capsule())
                        .accessibilityLabel(String(localized: "category.loading"))
                } else {
                    WLButton(String(localized: state.mode == .edit ? "category.form.save" : "category.form.create"), kind: .primary,
                             enabled: state.canSubmit) {
                        submit()
                    }
                    .frame(width: unit * 1.4)
                }
            }
        }
        .frame(height: 52)
    }

    private func submit() {
        guard let beforeSubmit else {
            owner.presenter.submit()
            return
        }
        preparing = true
        Task { @MainActor in
            await beforeSubmit()
            preparing = false
            owner.presenter.submit()
        }
    }
}
