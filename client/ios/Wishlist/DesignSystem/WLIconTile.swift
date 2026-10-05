import SwiftUI

/// 아이콘 타일. 기본 44pt·모서리 s 14·`iconTile` 면(흰 카드 위 홈 할 일 카드, 결정 2026-10-02).
/// 목적 카드·접힌 띠는 `color: card`, 상태 타일은 상태 색을 넘긴다.
struct WLIconTile<Icon: View>: View {
    var size: CGFloat = 44
    var radius: CGFloat = WishlistTokens.Radius.s
    var color: Color?
    @ViewBuilder var icon: () -> Icon

    @Environment(\.wlColors) private var c

    var body: some View {
        icon()
            .frame(width: size, height: size)
            .background(color ?? c.iconTile, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}
