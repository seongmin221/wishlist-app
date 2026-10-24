import Shared
import SwiftUI

/// 홈 탭 첫 화면(C3, C7). 로그인 전은 FHomeLoggedOut(로그인 카드 + 분석 대기), 로그인 뒤는 FHome(할 일: 분석 대기 +
/// HOME-01 세 그룹, 비교 중인 목적), 당겨서 새로고침이 재전송+HOME-01이다.
/// HOME-01은 홈이 보일 때(`appeared`: 첫 진입, 다른 탭·쌓인 화면에서 돌아옴)와 홈이 보이는 채 앱이 돌아올 때
/// (`foreground`)만 읽는다. 앱 전체 foreground 신호(`AppSignals`)는 그대로 둔다(겹침은 전송 조정기가 하나로 합친다).
/// `Loading`(복원 전·계정 전환 중)은 머리만 그려 이전 계정의 줄이 비치지 않게 한다(Android `HomeScreen`과 같다).
/// 당겨서 새로고침은 `.refreshable`의 시스템 indicator를 쓴다(보드에 indicator 모양이 없다).
struct HomeScreen: View {
    @Environment(HomePresenterOwner.self) private var home
    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.scenePhase) private var phase
    @Environment(\.wlRuntime) private var runtime
    @State private var gate = FlowForegroundGate()

    var body: some View {
        let state = home.state
        WLTabScrollView(tab: .home) {
            VStack(alignment: .leading, spacing: 0) {
                HomeHeader(caption: caption(state))
                switch onEnum(of: state) {
                case .loading: EmptyView()
                case .loggedOut(let s): HomeLoggedOutView(pending: s.pending)
                case .loggedIn(let s): HomeLoggedInView(state: s)
                }
            }
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .modifier(HomeRefresh(enabled: state is HomeStateLoggedIn, home: home))
        .background(c.background)
        // "방금" → "1분 전" while the screen stays open (ends when the screen goes away).
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                home.tick()
            }
        }
        // HOME-01 waits for the runtime (Android `runtime.ready.first { it }`): before it the gated repository
        // answers RUNTIME_NOT_READY, and a restored session would flash the retry card.
        .task(id: visible) {
            guard visible else { return }
            await runtime?.purposeUntilReady()
            if !Task.isCancelled { home.appeared() }
        }
        .onChange(of: phase) { _, phase in
            if gate.shouldForeground(phase, visible: visible) { home.foreground() }
        }
    }

    /// The home root is the home tab's top entry and that tab is shown.
    private var visible: Bool {
        nav.currentTab == .home && entryID != nil && nav.entries(.home).last?.id == entryID
    }

    private func caption(_ state: HomeState) -> String? {
        switch onEnum(of: state) {
        case .loading: nil
        case .loggedOut: String(localized: "home.logged.out.caption")
        case .loggedIn(let s): HomeRowText.todoCount(Int(s.todoCount))
        }
    }
}

/// 로그인 뒤에만 당겨서 새로고침(로그인 전에는 보낼 곳이 없다).
private struct HomeRefresh: ViewModifier {
    let enabled: Bool
    let home: HomePresenterOwner

    func body(content: Content) -> some View {
        if enabled {
            content.refreshable { await home.refresh() }
        } else {
            content
        }
    }
}

/// 머리(FHome header): 도현 28 제목이 위쪽 바 세로 가운데, 오른쪽 설정 원형 버튼. 아래 13/500 보조 줄
/// (로그인 전 "로그인 전", 로그인 뒤 "할 일 N개").
private struct HomeHeader: View {
    let caption: String?

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WLTopBar(sidePadding: 0) {
                EmptyView()
            } center: {
                WLText(WLTab.home.label, .display28, maxLines: 1)
                    .accessibilityAddTraits(.isHeader)
            } trailing: {
                WLCircleButton(.settings, label: String(localized: "settings.title")) {
                    nav.push(AppDestination.settings.route, sourceKey: "home/settings")
                }
            }
            if let caption {
                WLText(caption, HomeStyles.caption, color: c.textSecondary, maxLines: 1)
            }
        }
    }
}

/// 보드 FHome·FHomeLoggedOut의 글자 크기(토큰에 없는 크기는 가장 가까운 스타일에서 크기만 바꾼다).
enum HomeStyles {
    static let caption = WLTextStyle.label.resized(13)
    static let sectionLabel = WLTextStyle.label.resized(13)
    static let cardTitle = WLTextStyle.bodyBold.resized(17)
    static let cardSubtitle = WLTextStyle.body.resized(13)
    static let bullet = WLTextStyle.body.resized(14, lineHeight: 14 * 1.6)
    static let count = WLTextStyle.price.resized(26)
    static let link = WLTextStyle.body.resized(13)
}

/// 섹션: 위 간격 32, 13/500 이름, 아래 내용과 12.
struct HomeSection<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            WLText(label, HomeStyles.sectionLabel, color: c.textSecondary)
                .accessibilityAddTraits(.isHeader)
            content()
        }
        .padding(.top, WishlistTokens.Space.s32)
    }
}

/// 펼친 카드 안의 링크 한 줄: 묶음 면(`sheetField`, 모서리 20, 패딩 10) 위 아이콘 타일(카드색)·host·상태 줄.
/// `original`이 있으면 오른쪽에 "원본"(로그인 전 분석 대기만, Ruling 15: 로그인 뒤 분류 중 줄은 오른쪽 동작이 없다).
/// 줄을 누르면 상세로 간다(C4, `HomeRow.target`): 서버 상품은 `.item`, 이 기기의 링크는 `.local`(가로 밀기).
/// VoiceOver는 줄을 host·상태로 읽고 동작 이름 "상세 보기"를 힌트로, "원본"은 "원본 열기"로 읽는다.
struct HomeLinkRow: View {
    let row: HomeRow
    let icon: WLLineIcon
    let tileSize: CGFloat
    var showsOriginal = false

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            Button(action: open) {
                HStack(spacing: WishlistTokens.Space.s12) {
                    WLIconTile(size: tileSize, color: c.card) { WLIcon(icon) }
                    VStack(alignment: .leading, spacing: 2) {
                        WLText(row.host, .bodyStrong, maxLines: 1)
                            .truncationMode(.tail)
                        WLText(HomeRowText.meta(row), .label, color: c.textSecondary, maxLines: 1)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint(String(localized: String.LocalizationValue(HomeRowText.openDetailKey)))
            if showsOriginal { OriginalLink(url: row.sourceUrl, sourceKey: "home/row/\(row.key)/original") }
        }
        .padding(10)
        .background(c.sheetField, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
    }

    private func open() {
        let source = "home/row/\(row.key)"
        switch onEnum(of: row.target) {
        case .item(let target): nav.push(AppDestination.item(target.itemId).route, sourceKey: source)
        case .local(let target): nav.push(AppDestination.local(target.submissionId).route, sourceKey: source)
        }
    }
}

/// "원본": C4 PR B — 앱 안 웹뷰(`.web`)로 연다. `http`/`https`가 아니면 흐리게 두고 누를 수 없다. VoiceOver 이름은 "원본 열기".
struct OriginalLink: View {
    let url: String
    let sourceKey: String

    @Environment(\.wlNavigator) private var nav

    var body: some View {
        let page = WebPageURL(string: url)
        Button {
            if let page { nav.push(AppDestination.web(page).route, sourceKey: sourceKey) }
        } label: {
            HStack(spacing: WishlistTokens.Space.s4) {
                WLText(String(localized: "home.original"), HomeStyles.link, maxLines: 1)
                WLIcon(.external, size: 14)
            }
            .padding(.horizontal, 6)
            .frame(minHeight: WishlistTokens.Space.minTouch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(page == nil)
        .opacity(page == nil ? wlDisabledOpacity : 1)
        .accessibilityLabel(String(localized: String.LocalizationValue(HomeRowText.openOriginalKey)))
    }
}
