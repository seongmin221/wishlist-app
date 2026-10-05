import SwiftUI

/// 카테고리 탭 데모: 왼쪽 상위 레일 + 오른쪽 세부 유형 알약 칩(칩 → 목록은 자리 표시 면 이동).
struct DemoCategoryScreen: View {
    @Environment(\.wlColors) private var c
    @State private var selected = 2

    var body: some View {
        WLTabScrollView(tab: .category) {
            VStack(alignment: .leading, spacing: 0) {
                DemoTabHeader(title: "카테고리", subtitle: "상품 69개")
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                Spacer().frame(height: WishlistTokens.Space.s16)
                Rectangle().fill(c.line).frame(height: 1)
                HStack(alignment: .top, spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(DemoContent.railCategories.enumerated()), id: \.offset) { i, name in
                            railRow(name, on: i == selected) { selected = i }
                        }
                    }
                    .frame(width: 112)
                    VStack(alignment: .leading, spacing: 0) {
                        WLText(DemoContent.railCategories[selected], .label, color: c.textSecondary)
                        Spacer().frame(height: WishlistTokens.Space.s16)
                        DemoFlowLayout {
                            ForEach(DemoContent.chips, id: \.id) { DemoSurfaceChip(chip: $0, sourceKey: "category/chip/\($0.id)") }
                            WLAddChip(text: "추가") {}
                        }
                    }
                    .padding(.trailing, WishlistTokens.Space.screenMargin)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.top, WishlistTokens.Space.s16)
            }
        }
    }

    private func railRow(_ name: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Rectangle().fill(on ? c.text : c.background).frame(width: 3)
                WLText(name, on ? .bodyBold : .body, color: on ? c.text : c.textSecondary)
                    .padding(.leading, 17)
                    .padding(.trailing, WishlistTokens.Space.s8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}
