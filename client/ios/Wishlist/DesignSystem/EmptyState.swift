import SwiftUI

/// 빈 상태: 아이콘 타일(56·모서리 m 20, 시트 위에서는 `sheetField`) + 제목(18/700) + 설명, 가운데 정렬.
/// 목록 영역 가운데에 놓는 것은 호출하는 쪽(`.frame(maxWidth: .infinity, maxHeight: .infinity)` + 이 컴포넌트).
struct EmptyState<Icon: View>: View {
    let title: String
    let description: String
    @ViewBuilder var icon: () -> Icon

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s12) {
            // 시트 면(흰색) 위에서는 카드색 타일이 보이지 않으므로 묶음 면(`sheetField`)을 쓴다(FPurposeDetailEmptyL).
            WLIconTile(size: 56, radius: WishlistTokens.Radius.m, color: onSheet ? c.sheetField : c.card, icon: icon)
            WLText(title, WLTextStyle.title.resized(18), color: c.text, alignment: .center)
            WLText(description, WLTextStyle.body.resized(14, lineHeight: 14 * 1.5), color: c.textSecondary, alignment: .center)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, WishlistTokens.Space.s32)
        .accessibilityElement(children: .combine)
    }
}
