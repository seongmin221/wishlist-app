import SwiftUI

/// 목적 6색. 면 색은 두 테마 같고, 라이트에서만 표시용 테두리 값을 1pt 테두리로 쓴다.
enum WLPurposeColor: CaseIterable {
    case coral, mustard, periwinkle, cyan, mint, pink

    var face: Color {
        switch self {
        case .coral: WishlistTokens.Purpose.coral
        case .mustard: WishlistTokens.Purpose.mustard
        case .periwinkle: WishlistTokens.Purpose.periwinkle
        case .cyan: WishlistTokens.Purpose.cyan
        case .mint: WishlistTokens.Purpose.mint
        case .pink: WishlistTokens.Purpose.pink
        }
    }

    var lightBorder: Color {
        switch self {
        case .coral: WishlistTokens.Purpose.LightBorder.coral
        case .mustard: WishlistTokens.Purpose.LightBorder.mustard
        case .periwinkle: WishlistTokens.Purpose.LightBorder.periwinkle
        case .cyan: WishlistTokens.Purpose.LightBorder.cyan
        case .mint: WishlistTokens.Purpose.LightBorder.mint
        case .pink: WishlistTokens.Purpose.LightBorder.pink
        }
    }
}

/// 목적 색 점. 상품 카드 10pt, 확인창·시트 목적 줄 12pt.
struct PurposeDot: View {
    let color: WLPurposeColor
    var size: CGFloat = 10

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Circle()
            .fill(color.face)
            .overlay { if scheme != .dark { Circle().strokeBorder(color.lightBorder, lineWidth: 1) } }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
