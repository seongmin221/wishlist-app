import SwiftUI

/// 탭 바 높이(64 이상)와 아래 여백(화면 아래 끝에서 24, 보드 FHomeL `bottom: 24px`). 홈 표시줄(아래 약 13pt)보다 위다.
/// 탭 첫 화면(`WLTabScrollView`)이 내용 끝에 이만큼 비워 둔다.
let wlTabBarHeight: CGFloat = 64
let wlTabBarBottomGap: CGFloat = WishlistTokens.Space.s24

/// 먹색 알약 바 + 흰 선택 알약(디자인 결정 2026-10-02·2026-10-05). 알약은 250 `standard`로 미끄러지고,
/// 글자색(흰색 ↔ 먹색)·굵기(400 ↔ 700)는 125에 바꾼다(motion.md 3절).
/// 탭 글자는 글자 크기를 `xxLarge`(약 1.24배)까지만 따른다(칸 폭 안에 "카테고리"가 들어가야 한다. Android는 배율 1.3).
struct WLTabBar: View {
    let current: WLTab
    let onSelect: (WLTab) -> Void

    @Environment(\.wlColors) private var c
    @State private var labelTab: WLTab?

    var body: some View {
        let shown = labelTab ?? current
        HStack(spacing: 0) {
            ForEach(WLTab.allCases) { tab in
                let selected = tab == shown
                let color = selected ? c.onTabPill : c.onTabBar
                Button { onSelect(tab) } label: {
                    HStack(spacing: 6) {
                        WLTabIcon(tab: tab, color: color)
                        WLText(tab.label, selected ? WLTextStyle.bodyBold.resized(15) : WLTextStyle.body.resized(15), color: color)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.label)
                .accessibilityAddTraits(tab == current ? [.isButton, .isSelected] : [.isButton])
            }
        }
        .background(alignment: .leading) {
            GeometryReader { proxy in
                let w = proxy.size.width / CGFloat(WLTab.allCases.count)
                Capsule()
                    .fill(c.tabPill)
                    .frame(width: w, height: proxy.size.height)
                    .offset(x: CGFloat(WLTab.allCases.firstIndex(of: current) ?? 0) * w)
                    .animation(WishlistTokens.Curve.standard.animation(ms: WishlistTokens.Motion.tabPill), value: current)
            }
        }
        .padding(WishlistTokens.Space.s8)
        .frame(minHeight: wlTabBarHeight)
        .background(c.tabBar, in: Capsule())
        .padding(.horizontal, WishlistTokens.Space.s16)
        .padding(.bottom, wlTabBarBottomGap)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .task(id: current) {
            guard labelTab != nil, labelTab != current else {
                labelTab = current
                return
            }
            try? await Task.sleep(nanoseconds: UInt64(WishlistTokens.Motion.tabLabelSwapAt) * 1_000_000)
            if !Task.isCancelled { labelTab = current }
        }
    }
}

/// 보드의 24 격자 선 아이콘(선 1.8)을 20 크기로 그린다(FHomeL 탭 바 svg).
private struct WLTabIcon: View {
    let tab: WLTab
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let k = size.width / 24
            ctx.scaleBy(x: k, y: k)
            let stroke = StrokeStyle(lineWidth: 1.8, lineJoin: .round)
            switch tab {
            case .home:
                var p = Path()
                p.move(to: CGPoint(x: 4, y: 11))
                p.addLine(to: CGPoint(x: 12, y: 4))
                p.addLine(to: CGPoint(x: 20, y: 11))
                p.addLine(to: CGPoint(x: 20, y: 20))
                p.addLine(to: CGPoint(x: 15, y: 20))
                p.addLine(to: CGPoint(x: 15, y: 14))
                p.addLine(to: CGPoint(x: 9, y: 14))
                p.addLine(to: CGPoint(x: 9, y: 20))
                p.addLine(to: CGPoint(x: 4, y: 20))
                p.closeSubpath()
                ctx.stroke(p, with: .color(color), style: stroke)
            case .category:
                for (x, y) in [(4.0, 4.0), (13.0, 4.0), (4.0, 13.0), (13.0, 13.0)] {
                    ctx.stroke(Path(roundedRect: CGRect(x: x, y: y, width: 7, height: 7), cornerRadius: 2), with: .color(color), style: stroke)
                }
            case .purpose:
                ctx.stroke(Path(roundedRect: CGRect(x: 4, y: 8, width: 16, height: 12), cornerRadius: 3), with: .color(color), style: stroke)
                var line = Path()
                line.move(to: CGPoint(x: 7, y: 5))
                line.addLine(to: CGPoint(x: 17, y: 5))
                ctx.stroke(line, with: .color(color), style: stroke)
            }
        }
        .frame(width: 20, height: 20)
        .accessibilityHidden(true)
    }
}
