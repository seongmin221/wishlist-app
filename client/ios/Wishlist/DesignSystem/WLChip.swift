import SwiftUI

/// 알약 칩(높이 40 이상). 선택됨=반전색 면 + 700 글자(체크 없음). `count`는 이름 뒤 보조색 숫자.
struct WLChip: View {
    let text: String
    var selected = false
    var count: Int?
    let action: () -> Void

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    var body: some View {
        let bg = selected ? c.text : (onSheet ? c.sheetField : c.chip)
        let fg = selected ? c.onInverse : c.text
        Button(action: action) {
            HStack(spacing: 7) {
                WLText(text, selected ? .bodyBold : .body, color: fg, maxLines: 1)
                if let count {
                    WLText(String(count), WLTextStyle.price.resized(WLTextStyle.body.size), color: selected ? fg : c.textSecondary, maxLines: 1)
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .background(bg, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// 점선 "+ 추가" 칩(1.5pt 점선 테두리, + 아이콘 16·선 2, 간격 6). 출처: FCategoryHomeL 추가 칩.
struct WLAddChip: View {
    let text: String
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                // 보드 SVG `M12 5v14M5 12h14`(24 격자, 선 2)를 16 크기로 그린다.
                Path { p in
                    let k: CGFloat = 16.0 / 24
                    p.move(to: CGPoint(x: 12 * k, y: 5 * k))
                    p.addLine(to: CGPoint(x: 12 * k, y: 19 * k))
                    p.move(to: CGPoint(x: 5 * k, y: 12 * k))
                    p.addLine(to: CGPoint(x: 19 * k, y: 12 * k))
                }
                .stroke(c.textSecondary, lineWidth: 2 * 16.0 / 24)
                .frame(width: 16, height: 16)
                .accessibilityHidden(true)
                WLText(text, .body, color: c.textSecondary, maxLines: 1)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .overlay(Capsule().inset(by: 0.75).stroke(c.textSecondary, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
