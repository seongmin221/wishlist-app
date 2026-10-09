import Shared
import SwiftUI

/// 첫 실행 안내(앱 루트 위 레이어)인지, 홈 로그인 카드·설정 "로그인"에서 push로 연 화면인지.
enum LoginMode { case firstRun, pushed }

private let pointStyle = WLTextStyle.body.resized(15, lineHeight: 15 * 1.5)
private let laterStyle = WLTextStyle.body.resized(15)

/// FLogin. 로그인 중(`signingIn`)에는 세 버튼을 모두 막는다. 실패는 C3에서 화면에 남기지 않는다(fake는 RELEASE에서만
/// 실패하고, 그때 버튼은 아무 일도 하지 않는다 — 인증 연결 단계까지). Android `LoginScreen`과 같은 동작.
/// - firstRun: "나중에 하기" = 다시 자동으로 띄우지 않음. 로그인하면 루트가 레이어를 내린다.
/// - pushed: "나중에 하기" = 뒤로. 로그인 전 → 로그인으로 바뀌면 이 화면을 닫는다.
struct LoginScreen: View {
    let mode: LoginMode

    @Environment(AccountPresenterOwner.self) private var account
    @Environment(\.wlNavigatorStorage) private var nav
    @Environment(\.wlColors) private var c
    @State private var signedOutWhenOpened: Bool?

    var body: some View {
        let idle = account.idle
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s24) {
                WLIconTile(size: 64, radius: WishlistTokens.Radius.m, color: WishlistTokens.Purpose.coral) {
                    WLIcon(.heart, size: 30, color: WishlistTokens.Purpose.onPurpose)
                }
                .accessibilityHidden(true)
                WLText(String(localized: "login.title"), .display28TwoLine)
                    .accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
                    ForEach(["login.point.share", "login.point.fetch", "login.point.devices"], id: \.self) { key in
                        HStack(spacing: WishlistTokens.Space.s12) {
                            WLIconTile(size: 32, radius: WishlistTokens.Radius.xs, color: WishlistTokens.Purpose.coral) {
                                WLIcon(.check, color: WishlistTokens.Purpose.onPurpose)
                            }
                            .accessibilityHidden(true)
                            WLText(NSLocalizedString(key, comment: ""), pointStyle)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            VStack(spacing: 10) {
                WLButton(String(localized: "login.apple"), kind: .primary, enabled: idle, minHeight: 56) { account.signIn(.apple) }
                WLButton(String(localized: "login.google"), kind: .secondary, enabled: idle, minHeight: 56) { account.signIn(.google) }
                Button(action: later) {
                    WLText(String(localized: "login.later"), laterStyle, color: c.textSecondary, maxLines: 1)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!idle)
            }
        }
        .padding(.horizontal, WishlistTokens.Space.s24)
        .padding(.bottom, WishlistTokens.Space.s40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 첫 실행 레이어 아래 탭 셸로 누름이 새지 않게 면 전체가 받는다.
        .background(c.background.ignoresSafeArea().contentShape(Rectangle()).onTapGesture {})
        .onAppear {
            if signedOutWhenOpened == nil { signedOutWhenOpened = account.state.account == nil }
        }
        .task(id: account.state.account?.accountId) { await closeAfterSignIn() }
    }

    private func later() {
        switch mode {
        case .firstRun: account.skipFirstRunLogin()
        case .pushed: nav?.pop()
        }
    }

    /// pushed: 열 때 로그인 전이었고 이제 로그인했으면 닫는다. 여는 전환이 아직 끝나지 않았으면 끝난 뒤 닫는다
    /// (전환 중 pop은 무시된다).
    private func closeAfterSignIn() async {
        guard mode == .pushed, signedOutWhenOpened == true, account.state.account != nil, let nav else { return }
        while nav.isTransitioning {
            try? await Task.sleep(for: .milliseconds(20))
            if Task.isCancelled { return }
        }
        nav.pop()
    }
}
