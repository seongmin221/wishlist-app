import SwiftUI

/// 44 원형 아이콘 버튼(카드색 면): 위쪽 바의 뒤로, 홈 오른쪽 위 설정. `label`은 VoiceOver 이름이다(Android `WLCircleButton`).
struct WLCircleButton: View {
    let icon: WLLineIcon
    let label: String
    let action: () -> Void

    @Environment(\.wlColors) private var c

    init(_ icon: WLLineIcon, label: String, action: @escaping () -> Void) {
        self.icon = icon
        self.label = label
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            WLIcon(icon)
                .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                .background(c.card, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
