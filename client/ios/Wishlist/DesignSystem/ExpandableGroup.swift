import SwiftUI

/// 펼치기 화살표(아래 방향 V). 펼치면 180도 뒤집힌다(200 ease).
struct WLChevron: View {
    let expanded: Bool
    var color: Color?

    @Environment(\.wlColors) private var c

    var body: some View {
        Path { p in
            p.move(to: CGPoint(x: 5.5, y: 7.75))
            p.addLine(to: CGPoint(x: 10, y: 12.25))
            p.addLine(to: CGPoint(x: 14.5, y: 7.75))
        }
        .stroke(color ?? c.text, style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
        .frame(width: 20, height: 20)
        .rotationEffect(.degrees(expanded ? 180 : 0))
        .animation(WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureArrow), value: expanded)
        .accessibilityHidden(true)
    }
}

/// 접히는 묶음 면(머리 줄 + 펼침 내용). 펼침 내용은 높이·opacity 200 `ease`.
/// 머리 줄 전체가 접기·펼치기 버튼이다(확인창 안 상품 목록). 홈 할 일 카드처럼 화살표만 따로 누르려면 `WLChevron`을 직접 쓴다.
struct ExpandableGroup<Header: View, Content: View>: View {
    @Binding var expanded: Bool
    @ViewBuilder var header: () -> Header
    @ViewBuilder var content: () -> Content

    var body: some View {
        WLCard(radius: WishlistTokens.Radius.m) {
            VStack(spacing: 0) {
                Button { expanded.toggle() } label: {
                    HStack {
                        header()
                        WLChevron(expanded: expanded)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, minHeight: WishlistTokens.Space.minTouch, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(expanded ? "펼침" : "접힘")
                if expanded {
                    VStack(alignment: .leading, spacing: 0) { content() }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .transition(.opacity)
                }
            }
            .animation(WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureContent), value: expanded)
        }
    }
}
