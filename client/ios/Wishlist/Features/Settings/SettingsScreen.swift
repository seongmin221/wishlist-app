import Shared
import SwiftUI

private let sectionLabel = WLTextStyle.label.resized(13)
private let rowTitle = WLTextStyle.body.resized(16)
private let rowSubtitle = WLTextStyle.body.resized(13)
private let emailStyle = WLTextStyle.bodyBold.resized(16)
private let hintStyle = WLTextStyle.body.resized(15, lineHeight: 15 * 1.5)
private let versionStyle = WLTextStyle.price.resized(14)

/// FSettings·FSettingsLoggedOut(+ 로그아웃·웹뷰 데이터 삭제 확인창). 로그아웃은 먹색, 웹뷰 데이터 삭제는 빨강 확인.
/// "방금 삭제했어요"는 이 화면 수명 동안만(C3-D5 h). 오픈소스 라이선스 줄은 C12까지 숨긴다(C3-D5 b). 버전은
/// `CFBundleShortVersionString`(C3-D5 c). Android `SettingsScreen`과 같은 동작.
struct SettingsScreen: View {
    @Environment(AccountPresenterOwner.self) private var account
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.wlColors) private var c
    @State private var webViewCleared = false

    var body: some View {
        VStack(spacing: 0) {
            WLTopBar(background: c.background) {
                WLCircleButton(.back, label: String(localized: "settings.back")) { nav.pop() }
            } center: {
                WLText(String(localized: "settings.title"), .title, maxLines: 1)
                    .wlArrivalFocus()
            } trailing: {
                EmptyView()
            }
            ScrollView {
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s24) {
                    SettingsSection(label: String(localized: "settings.account")) { accountRows }
                    SettingsSection(label: String(localized: "settings.original.links")) {
                        SettingsRow(
                            title: String(localized: "settings.webview.clear"),
                            subtitle: String(localized: webViewCleared ? "settings.webview.cleared" : "settings.webview.clear.hint"),
                            action: { overlay?.showDialog(webViewDialog) }
                        ) { WLIcon(.chevronRight, size: 16) }
                    }
                    SettingsSection(label: String(localized: "settings.app.info")) {
                        SettingsRow(title: String(localized: "settings.version"), action: nil) {
                            WLText(Self.version, versionStyle, color: c.textSecondary, maxLines: 1)
                        }
                    }
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.top, WishlistTokens.Space.s8)
                .padding(.bottom, 60)
            }
        }
        .background(c.background.ignoresSafeArea())
    }

    @ViewBuilder
    private var accountRows: some View {
        if let signedIn = account.state.account {
            AccountRow(account: signedIn)
            SettingsRow(title: String(localized: "settings.logout"), enabled: account.idle, action: { overlay?.showDialog(logoutDialog) }) {
                WLIcon(.chevronRight, size: 16)
            }
        } else {
            VStack(alignment: .leading, spacing: 14) {
                WLText(String(localized: "settings.login.hint"), hintStyle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                WLButton(String(localized: "settings.login"), kind: .primary, enabled: account.idle) {
                    nav.push(AppDestination.login.route, sourceKey: "settings/login")
                }
            }
            .padding(.init(top: 14, leading: 16, bottom: 16, trailing: 16))
        }
    }

    private var logoutDialog: WLDialogSpec {
        WLDialogSpec(
            title: String(localized: "logout.title"),
            bullets: [String(localized: "logout.line.device"), String(localized: "logout.line.kept"), String(localized: "logout.line.resume")],
            cancelText: String(localized: "dialog.cancel"),
            confirmText: String(localized: "settings.logout"),
            confirmKind: .primary,
            onConfirm: { [account] in account.signOut() }
        )
    }

    private var webViewDialog: WLDialogSpec {
        WLDialogSpec(
            title: String(localized: "webview.clear.title"),
            bullets: [
                String(localized: "webview.clear.line.login"), String(localized: "webview.clear.line.cookies"),
                String(localized: "webview.clear.line.kept"), String(localized: "webview.clear.line.irreversible"),
            ],
            cancelText: String(localized: "dialog.cancel"),
            confirmText: String(localized: "webview.clear.confirm"),
            confirmKind: .danger,
            onConfirm: { WebViewDataCleaner.clear { webViewCleared = true } }
        )
    }

    private static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
}

/// 섹션: 13/500 이름(좌우 4) + 묶음 카드(모서리 28, 위아래 4).
private struct SettingsSection<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(label, sectionLabel, color: c.textSecondary)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)
            WLCard {
                VStack(spacing: 0) { content() }
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

/// 계정 줄(높이 72 이상): 원형 아바타 · 이메일 16/700 · "○○로 로그인됨" 12/500.
private struct AccountRow: View {
    let account: AuthAccount

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            WLIconTile(radius: 22) { WLIcon(.person) }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                WLText(account.email, emailStyle, maxLines: 1)
                WLText(String(localized: account.provider == .google ? "settings.signed.in.google" : "settings.signed.in.apple"),
                       .label, color: c.textSecondary, maxLines: 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(minHeight: 72)
        .accessibilityElement(children: .combine)
    }
}

/// 설정 줄(높이 56 이상, 좌 16·우 14): 제목 16 + 보조 13, 오른쪽 화살표나 값. `action`이 없으면 누를 수 없다.
private struct SettingsRow<Trailing: View>: View {
    let title: String
    var subtitle: String?
    var enabled = true
    let action: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.wlColors) private var c

    var body: some View {
        if let action {
            Button(action: action) { content.contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.4)
        } else {
            content.accessibilityElement(children: .combine)
        }
    }

    private var content: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            VStack(alignment: .leading, spacing: 2) {
                WLText(title, rowTitle)
                if let subtitle { WLText(subtitle, rowSubtitle, color: c.textSecondary) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.init(top: 8, leading: 16, bottom: 8, trailing: 14))
        .frame(minHeight: 56)
    }
}
