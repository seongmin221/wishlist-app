import SwiftUI

/// 빈 상태: 아이콘 타일(56·모서리 m 20) + 제목(18/700) + 설명, 가운데 정렬.
/// 목록 영역 가운데에 놓는 것은 호출하는 쪽(`.frame(maxWidth: .infinity, maxHeight: .infinity)` + 이 컴포넌트).
struct EmptyState<Icon: View>: View {
    let title: String
    let description: String
    @ViewBuilder var icon: () -> Icon

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s12) {
            WLIconTile(size: 56, radius: WishlistTokens.Radius.m, color: c.card, icon: icon)
            WLText(title, WLTextStyle.title.resized(18), color: c.text, alignment: .center)
            WLText(description, WLTextStyle.body.resized(14, lineHeight: 14 * 1.5), color: c.textSecondary, alignment: .center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, WishlistTokens.Space.s32)
        .accessibilityElement(children: .combine)
    }
}
