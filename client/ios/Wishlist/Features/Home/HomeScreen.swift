import Shared
import SwiftUI

/// 홈 탭 첫 화면(C3). 로그인 전은 FHomeLoggedOut(로그인 카드 + 분석 대기), 로그인 뒤는 FHome 틀에 "분류 중" 카드만
/// (C3-D3: 이 계정의 미전송 + 캐시의 분석 중 항목, 다른 할 일 카드는 C7까지 숨김), 당겨서 새로고침이 재전송+갱신이다.
/// `Loading`(복원 전·계정 전환 중)은 머리만 그려 이전 계정의 줄이 비치지 않게 한다(Android `HomeScreen`과 같다).
/// 당겨서 새로고침은 `.refreshable`의 시스템 indicator를 쓴다(보드에 indicator 모양이 없다).
struct HomeScreen: View {
    @Environment(HomePresenterOwner.self) private var home
    @Environment(\.wlColors) private var c

    var body: some View {
        let state = home.state
        WLTabScrollView(tab: .home) {
            VStack(alignment: .leading, spacing: 0) {
                HomeHeader(caption: caption(state))
                switch onEnum(of: state) {
                case .loading: EmptyView()
                case .loggedOut(let s): HomeLoggedOutView(pending: s.pending)
                case .loggedIn(let s): HomeLoggedInView(processing: s.processing)
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
    }

    private func caption(_ state: HomeState) -> String? {
        switch onEnum(of: state) {
        case .loading: nil
        case .loggedOut: String(localized: "home.logged.out.caption")
        case .loggedIn(let s): HomeRowText.todoCount(s.processing.count)
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

/// 할 일 카드(분석 대기·분류 중): 갈 화면이 없어 카드 머리 전체가 펼치기이고, 오른쪽 화살표도 같은 동작이다(보드:
/// 두 버튼, 화살표 이름 "펼치기"/"접기"). 화살표 200 `ease`, 펼침 내용 높이·opacity 200 `ease`(motion.md 작은 동작).
struct HomeTodoCard<Rows: View>: View {
    let icon: WLLineIcon
    let title: String
    let subtitle: String
    let count: Int
    @Binding var expanded: Bool
    @ViewBuilder var rows: () -> Rows

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        WLCard {
            VStack(spacing: 0) {
                HStack(spacing: WishlistTokens.Space.s4) {
                    Button { expanded.toggle() } label: {
                        HStack(spacing: 14) {
                            WLIconTile { WLIcon(icon) }
                            VStack(alignment: .leading, spacing: 2) {
                                WLText(title, HomeStyles.cardTitle)
                                WLText(subtitle, HomeStyles.cardSubtitle, color: c.textSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            WLText("\(count)", HomeStyles.count, maxLines: 1)
                        }
                        .frame(minHeight: WishlistTokens.Space.minTouch)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(String(localized: expanded ? "wl.expanded" : "wl.collapsed"))
                    Button { expanded.toggle() } label: {
                        WLChevron(expanded: expanded)
                            .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: expanded ? "home.collapse" : "home.expand"))
                }
                .padding(.init(top: 16, leading: 16, bottom: 16, trailing: 6))
                WLDisclosureLayout(progress: expanded ? 1 : 0) {
                    VStack(spacing: WishlistTokens.Space.s8) { rows() }
                        .padding(.horizontal, 12)
                        .padding(.bottom, 12)
                }
                .clipped()
                .opacity(expanded ? 1 : 0)
                .allowsHitTesting(expanded)
                .accessibilityHidden(!expanded)
            }
            .animation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureContent), value: expanded)
        }
    }
}

/// 펼친 카드 안의 링크 한 줄: 묶음 면(`sheetField`, 모서리 20, 패딩 10) 위 아이콘 타일(카드색)·host·상태 줄.
/// `original`이 있으면 오른쪽에 "원본"(로그인 전 분석 대기만, Ruling 15: 로그인 뒤 분류 중 줄은 오른쪽 동작이 없다).
struct HomeLinkRow: View {
    let row: HomeRow
    let icon: WLLineIcon
    let tileSize: CGFloat
    var showsOriginal = false

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            WLIconTile(size: tileSize, color: c.card) { WLIcon(icon) }
            VStack(alignment: .leading, spacing: 2) {
                WLText(row.host, .bodyStrong, maxLines: 1)
                    .truncationMode(.tail)
                WLText(HomeRowText.meta(row), .label, color: c.textSecondary, maxLines: 1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if showsOriginal { OriginalLink(url: row.sourceUrl) }
        }
        .padding(10)
        .background(c.sheetField, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
    }
}

/// "원본": C3-D5 a — 시스템 브라우저로 연다(C4에서 웹뷰).
private struct OriginalLink: View {
    let url: String

    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            if let target = URL(string: url) { openURL(target) }
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
    }
}
