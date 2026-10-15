import Shared
import SwiftUI

/// The form's 취소 · 만들기/저장, pinned at the sheet's bottom (board: sheet footer above the home indicator,
/// above the keyboard while it is up). 취소 1 : 주 동작 1.4 (the `WLButtonPair` ratio); only the primary dims
/// when it cannot submit. While submitting both are locked and the primary shows progress.
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
                WLButton(String(localized: state.mode == .edit ? "category.form.save" : "category.form.create"), kind: .primary,
                         enabled: state.canSubmit || busy) {
                    submit()
                }
                .frame(width: unit * 1.4)
                .overlay(alignment: .trailing) {
                    if busy { ProgressView().tint(c.onInverse).padding(.trailing, WishlistTokens.Space.s20) }
                }
            }
        }
        .frame(height: 52)
        .allowsHitTesting(!busy)
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
