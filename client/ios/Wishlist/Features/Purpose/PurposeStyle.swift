import SwiftUI

/// Explicit domain keys; unknown response keys remain a neutral placeholder.
enum PurposeStyle {
    static let colors = ["coral", "mustard", "periwinkle", "cyan", "mint", "pink"]
    static let icons = ["heart", "home", "plane", "gift", "tent", "music", "star", "book"]
    static func color(_ key: String) -> Color {
        switch key {
        case "coral": WishlistTokens.Purpose.coral
        case "mustard": WishlistTokens.Purpose.mustard
        case "periwinkle": WishlistTokens.Purpose.periwinkle
        case "cyan": WishlistTokens.Purpose.cyan
        case "mint": WishlistTokens.Purpose.mint
        case "pink": WishlistTokens.Purpose.pink
        default: Color.gray.opacity(0.35)
        }
    }
    static func icon(_ key: String) -> WLLineIcon {
        switch key {
        case "heart": .heart
        case "home": .home
        case "plane": .plane
        case "gift": .gift
        case "tent": .tent
        case "music": .music
        case "star": .star
        case "book": .book
        default: .sorting
        }
    }
}

struct PurposeIconTile: View {
    let key: String
    var size: CGFloat = 44
    @Environment(\.wlColors) private var c
    var body: some View {
        WLIcon(PurposeStyle.icon(key), size: size * 0.5, color: c.text)
            .frame(width: size, height: size)
            .background(c.card, in: RoundedRectangle(cornerRadius: size * 0.32))
            .accessibilityHidden(true)
    }
}

/// Status wording for candidates whose product name has not arrived yet; keep real names verbatim.
enum PurposeCandidateText {
    static func title(name: String?, processing: Bool, bundle: Bundle = .main) -> String {
        if let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return name }
        return bundle.localizedString(forKey: processing ? "purpose.candidate.processing" : "purpose.candidate.incomplete", value: nil, table: nil)
    }
}
