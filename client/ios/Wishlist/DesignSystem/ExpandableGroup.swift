import SwiftUI

/// 펼치기 화살표(아래 방향 V). 펼치면 180도 뒤집힌다(200 ease).
struct WLChevron: View {
    let expanded: Bool
    var color: Color?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
        .animation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureArrow), value: expanded)
        .accessibilityHidden(true)
    }
}

/// 접히는 묶음 면(머리 줄 + 펼침 내용). 펼침 내용은 높이·opacity 200 `ease`.
/// 머리 줄 전체가 접기·펼치기 버튼이다(확인창 안 상품 목록). 홈 할 일 카드처럼 화살표만 따로 누르려면 `WLChevron`을 직접 쓴다.
struct ExpandableGroup<Header: View, Content: View>: View {
    @Binding var expanded: Bool
    @ViewBuilder var header: () -> Header
    @ViewBuilder var content: () -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                .accessibilityValue(expanded ? String(localized: "wl.expanded") : String(localized: "wl.collapsed"))
                WLDisclosureLayout(progress: expanded ? 1 : 0) {
                    VStack(alignment: .leading, spacing: 0) { content() }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .clipped()
                .opacity(expanded ? 1 : 0)
                .allowsHitTesting(expanded)
                .accessibilityHidden(!expanded)
            }
            .clipped()
            .animation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureContent), value: expanded)
        }
    }
}

/// 내용의 실제 높이를 접힘 진행값으로 줄인다. scale로 글자를 찌그러뜨리지 않는다(홈 할 일 카드도 쓴다).
struct WLDisclosureLayout: Layout {
    var progress: CGFloat
    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let size = subviews.first?.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) ?? .zero
        return CGSize(width: size.width, height: size.height * min(1, max(0, progress)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, anchor: .topLeading,
                              proposal: ProposedViewSize(width: bounds.width, height: nil))
    }
}
