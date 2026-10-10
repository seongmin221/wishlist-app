import Shared
import SwiftUI

/// FHomeLoggedOut: 로그인 카드 + 할 일 "분석 대기"(이 기기에만 있는 링크, 오래된 순, 줄마다 "원본"). 대기가 없으면 할 일
/// 섹션을 숨긴다. 줄을 누르면 로컬 대기 화면(C4), 오른쪽 "원본"은 시스템 브라우저다(PR A). 줄 key는 불투명 값이라 목록
/// 정체성으로만 쓴다.
struct HomeLoggedOutView: View {
    let pending: [HomeRow]

    @State private var expanded = false

    var body: some View {
        LoginCard()
            .padding(.top, WishlistTokens.Space.s32)
        if !pending.isEmpty {
            HomeSection(label: String(localized: "home.todo")) {
                HomeTodoCard(
                    icon: .clock,
                    title: String(localized: "home.pending.title"),
                    subtitle: String(localized: "home.pending.subtitle"),
                    count: pending.count,
                    expanded: $expanded
                ) {
                    ForEach(pending, id: \.key) { row in
                        HomeLinkRow(row: row, icon: .clock, tileSize: 44, showsOriginal: true)
                    }
                }
            }
        }
    }
}

/// 로그인 카드(패딩 20, 간격 14): 아이콘 타일 + 17/700 제목, 14/1.6 글머리표 두 줄, 로그인(먹색 주 버튼 → FLogin push).
private struct LoginCard: View {
    @Environment(AccountPresenterOwner.self) private var account
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        WLCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: WishlistTokens.Space.s12) {
                    WLIconTile { WLIcon(.person) }
                    WLText(String(localized: "home.login.card.title"), HomeStyles.cardTitle)
                        .accessibilityAddTraits(.isHeader)
                }
                VStack(alignment: .leading, spacing: 0) {
                    ForEach([String(localized: "home.login.card.fill"), String(localized: "home.login.card.devices")], id: \.self) { line in
                        HStack(alignment: .top, spacing: WishlistTokens.Space.s8) {
                            WLText("•", HomeStyles.bullet, color: c.textSecondary).accessibilityHidden(true)
                            WLText(line, HomeStyles.bullet, color: c.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .padding(.leading, 6)
                WLButton(String(localized: "home.login.button"), kind: .primary, enabled: account.idle) {
                    nav.push(AppDestination.login.route, sourceKey: "home/login")
                }
            }
            .padding(WishlistTokens.Space.s20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
