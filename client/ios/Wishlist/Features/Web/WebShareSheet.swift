import SwiftUI
import UIKit

/// FWebViewShare actions on the page's current URL (spec §4, D6). The web view stays open for all three.
@MainActor
enum WebShare {
    /// Safari (or the default browser). A false result does nothing.
    static func openInBrowser(_ url: URL) {
        UIApplication.shared.open(url, options: [:]) { _ in }
    }

    /// iOS shows no confirmation of its own for an app's copy, so the screen always shows "링크를 복사했어요" (D6).
    static func copy(_ url: URL) {
        UIPasteboard.general.string = url.absoluteString
    }

    /// `UIActivityViewController` over the top-most view controller of the key window.
    static func shareToOtherApp(_ url: URL) {
        guard let window = keyWindow, var top = window.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // iPad shows it as a popover: anchor it to the bottom center (the share button's bar).
        activity.popoverPresentationController?.sourceView = window
        activity.popoverPresentationController?.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.maxY - 60, width: 0, height: 0)
        top.present(activity, animated: true)
    }

    private static var keyWindow: UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.filter { $0.activationState == .foregroundActive }
        return (active.isEmpty ? scenes : active).flatMap(\.windows).first { $0.isKeyWindow }
            ?? scenes.flatMap(\.windows).first
    }
}

private let shareRowText = WLTextStyle.body.resized(16, lineHeight: 16 * 1.35)

/// 시트 내용(보드 FWebViewShare): 줄 최소 60, 40 타일(모서리 14, 시트 칸 색) + 16 글자, 간격 14, 줄 사이 4, 아래 20.
/// 누르면 시트가 닫히기 시작하고 동작한다(Android와 같다).
struct WebShareSheetContent: View {
    let onOpenBrowser: () -> Void
    let onCopy: () -> Void
    let onShareOther: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            WebShareRow(icon: .globe, label: String(localized: "webview.open.browser"), action: onOpenBrowser)
            WebShareRow(icon: .copy, label: String(localized: "webview.copy.link"), action: onCopy)
            WebShareRow(icon: .apps, label: String(localized: "webview.share.other"), action: onShareOther)
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, WishlistTokens.Space.s20)
    }
}

private struct WebShareRow: View {
    let icon: WLLineIcon
    let label: String
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                WLIconTile(size: 40, color: c.sheetField) { WLIcon(icon) }
                WLText(label, shareRowText, color: c.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
    }
}
