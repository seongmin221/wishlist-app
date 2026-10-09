import SwiftUI
import UIKit

/// The card shown after a share in the iOS extension (C3-D9 iOS). The extension does not link
/// Shared, so it has its own kinds (Ruling 2): LOCAL (no account mirrored), SAVED_OPEN_APP (an
/// account is mirrored; the app sends it when opened), INVALID (no link), STORE_FAILED (the inbox
/// write failed or no app-group container).
enum ShareCardKind: Equatable {
    case local, savedOpenApp, invalid, storeFailed

    var titleKey: String {
        switch self {
        case .local: "share.local.title"
        case .savedOpenApp: "share.saved.title"
        case .invalid: "share.invalid.title"
        case .storeFailed: "share.failed.title"
        }
    }

    var lineKey: String {
        switch self {
        case .local: "share.local.line"
        case .savedOpenApp: "share.saved.open.app"
        case .invalid: "share.invalid.line"
        case .storeFailed: "share.failed.line"
        }
    }

    var icon: WLLineIcon {
        switch self {
        case .local: .clockBold
        case .savedOpenApp: .checkBold
        case .invalid, .storeFailed: .warning
        }
    }

    /// Status tile colors (board FShareSaved / FShareSavedLocal; the failure cards are not on a board).
    func tile(dark: Bool) -> (surface: Color, icon: Color) {
        typealias L = WishlistTokens.Status.Light
        typealias D = WishlistTokens.Status.Dark
        switch self {
        case .local: return dark ? (D.pendingSurface, D.pendingIcon) : (L.pendingSurface, L.pendingIcon)
        case .savedOpenApp: return dark ? (D.doneSurface, D.doneIcon) : (L.doneSurface, L.doneIcon)
        case .invalid, .storeFailed: return dark ? (D.failedSurface, D.failedIcon) : (L.failedSurface, L.failedIcon)
        }
    }

    var title: String { Bundle.main.localizedString(forKey: titleKey, value: nil, table: nil) }
    var line: String { Bundle.main.localizedString(forKey: lineKey, value: nil, table: nil) }
}

private let lineStyle = WLTextStyle.body.resized(13, lineHeight: 13 * 1.45)

/// 저장 확인 카드: 패딩 16, 모서리 28, 카드색. 48 상태 타일(모서리 14) · 도현 20 제목 · 13/1.45 보조 줄(간격 6). 버튼 없음.
struct ShareCardView: View {
    let kind: ShareCardKind

    @Environment(\.colorScheme) private var scheme
    @Environment(\.wlColors) private var c

    var body: some View {
        let tile = kind.tile(dark: scheme == .dark)
        WLCard {
            HStack(spacing: 14) {
                WLIconTile(size: 48, color: tile.surface) { WLIcon(kind.icon, size: 22, color: tile.icon) }
                VStack(alignment: .leading, spacing: 6) {
                    WLText(kind.title, .display20)
                    WLText(kind.line, lineStyle, color: c.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(WishlistTokens.Space.s16)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Drives the card: hidden until the share is saved and the extension view is on screen, then
/// motion.md 6 — up (translateY +distance → 0, 340 `standard`), hold 1500, down (260 `accelerate`).
@MainActor
@Observable
final class ShareCardModel {
    static let inMs = 340
    static let holdMs = 1500
    static let outMs = 260

    private(set) var kind: ShareCardKind?
    private(set) var shown = false

    func present(_ kind: ShareCardKind) {
        self.kind = kind
    }

    /// Runs the whole timeline and returns after the card is off screen.
    func play() async {
        guard let kind else { return }
        UIAccessibility.post(notification: .announcement, argument: "\(kind.title), \(kind.line)")
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        withAnimation(reduceMotion ? nil : WishlistTokens.Curve.standard.animation(ms: Self.inMs)) { shown = true }
        try? await Task.sleep(for: .milliseconds(Self.inMs + Self.holdMs))
        withAnimation(reduceMotion ? nil : WishlistTokens.Curve.accelerate.animation(ms: Self.outMs)) { shown = false }
        try? await Task.sleep(for: .milliseconds(Self.outMs))
    }
}

/// The "other app" backdrop of boards FShareSaved* (page background: light #E9E9E9, dark #2A2A2A).
/// iOS 26 always wraps the extension in an opaque full-height system sheet (no public API makes it
/// transparent), so the extension paints this behind the card to keep the board's card contrast
/// (Ruling 17, a recorded board deviation). Not a design-system token. No shadow.
enum ShareBackdrop {
    static let light = Color(red: 0xE9 / 255, green: 0xE9 / 255, blue: 0xE9 / 255)
    static let dark = Color(red: 0x2A / 255, green: 0x2A / 255, blue: 0x2A / 255)
    static let uiColor = UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0x2A / 255, green: 0x2A / 255, blue: 0x2A / 255, alpha: 1)
            : UIColor(red: 0xE9 / 255, green: 0xE9 / 255, blue: 0xE9 / 255, alpha: 1)
    }

    static func color(dark: Bool) -> Color { dark ? Self.dark : Self.light }
}

/// The extension's whole UI: the board backdrop with only the card, 40 above the bottom (or 16
/// above the home indicator if that is higher), 16 from the sides. The travel is the larger of 140
/// and "card + bottom gap", so the card is fully off screen before and after.
struct ShareCardHost: View {
    let model: ShareCardModel

    @Environment(\.colorScheme) private var scheme
    @State private var cardHeight: CGFloat = 0

    var body: some View {
        GeometryReader { proxy in
            let bottom = max(WishlistTokens.Space.s40, proxy.safeAreaInsets.bottom + WishlistTokens.Space.s16)
            let distance = max(140, cardHeight + bottom)
            ZStack(alignment: .bottom) {
                ShareBackdrop.color(dark: scheme == .dark)
                if let kind = model.kind {
                    ShareCardView(kind: kind)
                        .frame(maxWidth: 480)
                        .background(GeometryReader { card in
                            Color.clear.onAppear { cardHeight = card.size.height }
                                .onChange(of: card.size.height) { _, h in cardHeight = h }
                        })
                        .padding(.horizontal, WishlistTokens.Space.s16)
                        .padding(.bottom, bottom)
                        .offset(y: model.shown ? 0 : distance)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
        }
        .ignoresSafeArea()
    }
}
