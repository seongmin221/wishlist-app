import SwiftUI

/// 누를 수 없는 버튼·메뉴 항목·링크를 흐리게 그리는 불투명도(Android `DISABLED_ALPHA`).
let wlDisabledOpacity = 0.4

enum WLButtonKind { case primary, secondary, danger }

/// 높이 52 이상 pill. 큰 글자 크기에서는 늘어난다. primary=반전색, secondary=카드색(시트·확인창 위는 sheetField),
/// danger=위험 빨강(확인창 안에서만).
struct WLButton: View {
    let title: String
    let kind: WLButtonKind
    var enabled = true
    var fillHeight = false
    /// 보드 기본 52. 로그인 화면의 계정 버튼은 56이다.
    var minHeight: CGFloat = 52
    let action: () -> Void

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    init(_ title: String, kind: WLButtonKind, enabled: Bool = true, minHeight: CGFloat = 52, action: @escaping () -> Void) {
        self.title = title
        self.kind = kind
        self.enabled = enabled
        self.minHeight = minHeight
        self.action = action
    }

    fileprivate init(_ title: String, kind: WLButtonKind, fillHeight: Bool, action: @escaping () -> Void) {
        self.title = title
        self.kind = kind
        self.fillHeight = fillHeight
        self.action = action
    }

    private var colors: (bg: Color, fg: Color) {
        switch kind {
        case .primary: (c.text, c.onInverse)
        case .secondary: (onSheet ? c.sheetField : c.card, c.text)
        case .danger: (WishlistTokens.Danger.surface, WishlistTokens.Danger.onSurface)
        }
    }

    var body: some View {
        let (bg, fg) = colors
        Button(action: action) {
            WLText(title, kind == .secondary ? .buttonMedium : .button, color: fg, alignment: .center, maxLines: 1)
                .padding(.horizontal, WishlistTokens.Space.s20)
                .padding(.vertical, WishlistTokens.Space.s12)
                .frame(maxWidth: .infinity, minHeight: minHeight, maxHeight: fillHeight ? .infinity : nil)
                .background(bg, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : wlDisabledOpacity)
    }
}

private struct WLWeight: LayoutValueKey {
    static let defaultValue: CGFloat = 1
}

/// 자식 폭을 가중치 비로 나눈다(취소 1 : 주 동작 1.4). 높이는 가장 큰 자식에 맞춘다.
private struct WLWeightedRow: Layout {
    var spacing: CGFloat

    private func widths(_ total: CGFloat, _ subviews: Subviews) -> [CGFloat] {
        let ws = subviews.map { $0[WLWeight.self] }
        let free = max(0, total - spacing * CGFloat(max(0, subviews.count - 1)))
        let sum = ws.reduce(0, +)
        return ws.map { sum > 0 ? free * $0 / sum : 0 }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total = proposal.width.flatMap { $0.isFinite ? $0 : nil } ?? (subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width } + spacing * CGFloat(max(0, subviews.count - 1)))
        let w = widths(total, subviews)
        let h = zip(subviews, w).map { $0.sizeThatFits(ProposedViewSize(width: $1, height: nil)).height }.max() ?? 0
        return CGSize(width: total, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let w = widths(bounds.width, subviews)
        let h = zip(subviews, w).map { $0.sizeThatFits(ProposedViewSize(width: $1, height: nil)).height }.max() ?? 0
        var x = bounds.minX
        for (sub, width) in zip(subviews, w) {
            sub.place(at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: width, height: h))
            x += width + spacing
        }
    }
}

/// 취소 왼쪽, 주 동작 오른쪽(더 넓다: 1 : 1.4).
struct WLButtonPair: View {
    let cancelText: String
    let primaryText: String
    let primaryKind: WLButtonKind
    let onCancel: () -> Void
    let onPrimary: () -> Void

    var body: some View {
        WLWeightedRow(spacing: WishlistTokens.Space.s12) {
            WLButton(cancelText, kind: .secondary, fillHeight: true, action: onCancel).layoutValue(key: WLWeight.self, value: 1)
            WLButton(primaryText, kind: primaryKind, fillHeight: true, action: onPrimary).layoutValue(key: WLWeight.self, value: 1.4)
        }
    }
}
