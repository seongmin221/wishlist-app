import Shared
import SwiftUI

/// FHome 로그인 뒤(C7). 할 일: 이 기기의 미전송 "분석 대기" 카드(로그인 전 카드와 같은 모양, 줄을 누르면 로컬 대기 화면,
/// Ruling 15로 오른쪽 동작 없음) 뒤에 HOME-01 그룹 카드(보드 순서, count > 0만). 모든 카드는 접혀 시작한다.
/// 첫 HOME-01이 실패하면 그룹 자리에 재시도, 그 뒤 실패는 마지막 요약을 두고 짧은 안내만 보인다. 할 일이 하나도 없으면
/// 할 일 섹션을 숨긴다. 비교 중인 목적은 HOME-01 `recentPurposes` 그대로(최대 3). 줄 key는 불투명 값이라 정체성으로만 쓴다.
struct HomeLoggedInView: View {
    let state: HomeStateLoggedIn

    @Environment(HomePresenterOwner.self) private var home
    @Environment(\.wlColors) private var c
    #if DEBUG
    @State private var expanded: Set<String> = C7DebugScreens.expandsHome
        ? ["pending", "CLASSIFICATION_REVIEW", "INFORMATION_COMPLETION", "ANALYSIS_IN_PROGRESS"] : []
    #else
    @State private var expanded: Set<String> = []
    #endif

    var body: some View {
        let showsTodo = !state.pending.isEmpty || !state.groups.isEmpty || !state.summaryLoaded
        if showsTodo {
            HomeSection(label: String(localized: "home.todo")) {
                if state.refreshFailed {
                    WLText(String(localized: "home.refresh.failed"), WLTextStyle.label.resized(13), color: c.textSecondary)
                }
                if !state.pending.isEmpty {
                    HomeTodoCard(
                        icon: .clock,
                        title: String(localized: "home.pending.title"),
                        subtitle: String(localized: "home.pending.subtitle.signed.in"),
                        count: state.pending.count,
                        expanded: binding("pending")
                    ) {
                        ForEach(state.pending, id: \.key) { row in
                            HomeLinkRow(row: row, icon: .clock, tileSize: 44)
                        }
                    }
                }
                ForEach(state.groups, id: \.group) { card in
                    HomeGroupTodoCard(card: card, expanded: binding(card.group.name))
                }
                if !state.summaryLoaded {
                    if state.summaryError != nil {
                        SummaryRetry { home.retry() }
                    } else {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 76)
                    }
                }
            }
        }
        if !state.purposes.isEmpty {
            HomePurposeSummary(purposes: state.purposes)
        }
    }

    private func binding(_ key: String) -> Binding<Bool> {
        Binding(get: { expanded.contains(key) }, set: { if $0 { expanded.insert(key) } else { expanded.remove(key) } })
    }
}

/// The first HOME-01 failed: a card-colored line with 다시 시도 instead of the group cards.
private struct SummaryRetry: View {
    let retry: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        WLCard {
            HStack(spacing: WishlistTokens.Space.s12) {
                WLIconTile { WLIcon(.warning, color: c.textSecondary) }
                WLText(String(localized: "home.summary.failed"), HomeStyles.cardSubtitle.resized(14))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: retry) {
                    WLText(String(localized: "detail.retry"), WLTextStyle.bodyBold, maxLines: 1)
                        .padding(.horizontal, WishlistTokens.Space.s16)
                        .frame(minHeight: WishlistTokens.Space.minTouch)
                        .background(c.sheetField, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(WishlistTokens.Space.s16)
        }
    }
}
