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
                WLText(text, selected ? .bodyBold : .body, color: fg)
                if let count {
                    WLText(String(count), WLTextStyle.price.resized(WLTextStyle.body.size), color: selected ? fg : c.textSecondary)
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

/// 점선 "+ 추가" 칩(1.5pt 점선 테두리).
struct WLAddChip: View {
    let text: String
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                WLText("+", .body, color: c.textSecondary)
                WLText(text, .body, color: c.textSecondary)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .overlay(Capsule().inset(by: 0.75).stroke(c.textSecondary, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
