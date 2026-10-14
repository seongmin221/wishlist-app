import SwiftUI

private let menuWidth: CGFloat = 200
private let menuGap: CGFloat = 8
private let menuBody = WLTextStyle.body.resized(15)

/// ⋯ 메뉴 카드: 시트색, 모서리 20, 폭 200, 항목 높이 52, 1pt 선색 테두리, 그림자·블러 없음. 끈 항목은 0.4로 흐리고 누를 수 없다.
struct WLMenuCard: View {
    let items: [WLMenuItem]
    let onItemClick: (WLMenuItem) -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                Button { onItemClick(item) } label: {
                    HStack(spacing: 12) {
                        item.icon
                        WLText(item.text, menuBody, color: c.text)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 18)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!item.enabled)
                .opacity(item.enabled ? 1 : wlDisabledOpacity)
            }
        }
        .padding(.vertical, 6)
        .frame(width: menuWidth)
        .background(c.sheet, in: shape)
        .overlay(shape.strokeBorder(c.line, lineWidth: 1))
        .clipShape(shape)
    }
}

/// ⋯ 메뉴(OverlayHost 안). 나타남 opacity·scale(.96 -> 1) 150 ease-out, 버튼 쪽 모서리 기준. 블러·막 없음.
/// 바깥 누르기로 닫힌다(전환 중에는 무시). 항목을 누르면 메뉴가 닫히기 시작하고 항목의 `onClick`이 불린다
/// (거기서 `showDialog`를 불러도 메뉴 닫기가 끝난 뒤에 열린다).
struct MenuLayer: View {
    let entry: OverlayEntry
    let state: OverlayHostState
    let anchor: CGRect
    let items: [WLMenuItem]

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var q = 0.0

    var body: some View {
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let rect = anchor.offsetBy(dx: -origin.x, dy: -origin.y)
            let margin = WishlistTokens.Space.s16
            let onRight = rect.midX > geo.size.width / 2
            let x = min(max(onRight ? rect.maxX - menuWidth : rect.minX, margin), max(margin, geo.size.width - menuWidth - margin))
            ZStack(alignment: .topLeading) {
                Color.clear.contentShape(Rectangle()).onTapGesture { state.requestDismiss(entry.id) }
                WLMenuCard(items: items) { item in
                    if state.requestDismiss(entry.id) { item.onClick() }
                }
                .scaleEffect(reduceMotion ? 1 : 0.96 + 0.04 * q, anchor: UnitPoint(x: onRight ? 1 : 0, y: 0))
                .opacity(q)
                .offset(x: x, y: rect.maxY + menuGap)
            }
        }
        .ignoresSafeArea()
        .task(id: entry.phase) { await run(entry.phase) }
    }

    private func run(_ phase: OverlayPhase) async {
        let anim: Animation? = reduceMotion ? nil : WishlistTokens.Curve.easeOut.animation(ms: WishlistTokens.Motion.overflowMenu)
        switch phase {
        case .opening:
            await overlayAnimate(anim) { q = 1 }
            if !Task.isCancelled { state.onOpened(entry.id) }
        case .closing:
            await overlayAnimate(anim) { q = 0 }
            if !Task.isCancelled { state.onClosed(entry.id) }
        case .open:
            break
        }
    }
}
