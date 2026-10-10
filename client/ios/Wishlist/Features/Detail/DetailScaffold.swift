import SwiftUI
import UIKit

/// 상세 화면(FProductDetail·FProductProcessing)의 글자 크기. 토큰에 없는 크기는 가장 가까운 스타일에서 크기만 바꾼다(Android `DetailStyles`).
enum DetailStyles {
    static let brand = WLTextStyle.bodyBold
    static let name = WLTextStyle.title
    static let price = WLTextStyle.price.resized(24)
    static let priceNote = WLTextStyle.body.resized(13, lineHeight: 13 * 1.5)
    static let note = WLTextStyle.body.resized(14, lineHeight: 14 * 1.6)
    static let caption = WLTextStyle.label.resized(13)
    static let infoValue = WLTextStyle.bodyBold.resized(15)
    static let infoEmpty = WLTextStyle.body.resized(15)
    static let notice = WLTextStyle.body.resized(13)
    static let status = WLTextStyle.title.resized(18)
}

/// 상세 틀 배치 값(보드·결정 2026-10-07, 안전 영역 안에서 잰다).
enum DetailLayout {
    /// 위쪽 바 버튼 아래 12(안전 영역 + 68): 사진 칸·첫 내용이 시작하는 자리.
    static let contentTop: CGFloat = WLTopBarMetrics.buttonTop(safeTop: 0) + WLTopBarMetrics.button + WishlistTokens.Space.s12
    /// 짧은 안내 줄: 위쪽 바 아래 8.
    static let noticeTop: CGFloat = WLTopBarMetrics.bottom(safeTop: 0) + WishlistTokens.Space.s8
    /// 하단 바: 위 12 + 버튼 56 + 화면 아래 끝에서 36(보드, 홈 표시줄 포함).
    static let barTop: CGFloat = WishlistTokens.Space.s12
    static let barBottom: CGFloat = 36
    static let barButton: CGFloat = 56
    /// 하단 바가 있을 때 스크롤 내용 끝 여백(보드 padding-bottom 140), 없을 때 40.
    static let bottomWithBar: CGFloat = 140
    static let bottomWithoutBar: CGFloat = WishlistTokens.Space.s40
    /// 분석 중·로컬 대기 사진 자리 높이.
    static let waitingHeight: CGFloat = 222
}

/// 상태 타일 색 쌍(면, 아이콘).
struct DetailTile {
    let surface: Color
    let icon: Color

    static func analyzing(_ dark: Bool) -> DetailTile {
        dark ? DetailTile(surface: WishlistTokens.Status.Dark.analyzingSurface, icon: WishlistTokens.Status.Dark.analyzingIcon)
            : DetailTile(surface: WishlistTokens.Status.Light.analyzingSurface, icon: WishlistTokens.Status.Light.analyzingIcon)
    }

    /// 보드 홈 "분석 대기"의 대기(pending) 상태 색.
    static func pending(_ dark: Bool) -> DetailTile {
        dark ? DetailTile(surface: WishlistTokens.Status.Dark.pendingSurface, icon: WishlistTokens.Status.Dark.pendingIcon)
            : DetailTile(surface: WishlistTokens.Status.Light.pendingSurface, icon: WishlistTokens.Status.Light.pendingIcon)
    }

    static func failed(_ dark: Bool) -> DetailTile {
        dark ? DetailTile(surface: WishlistTokens.Status.Dark.failedSurface, icon: WishlistTokens.Status.Dark.failedIcon)
            : DetailTile(surface: WishlistTokens.Status.Light.failedSurface, icon: WishlistTokens.Status.Light.failedIcon)
    }
}

/// 짧은 안내(3초). `serial`이 바뀌면 다시 보인다.
struct BriefNotice: Equatable {
    let serial: Int
    let text: String
}

/// 상세 공통 틀(Android `DetailScaffold`): 스크롤 본문 위에 뒤로·(선택)⋯가 떠 있는 위쪽 바 56(안전 영역 + 6, 좌우 20),
/// 하단 고정 "원본 보기"(PR B: 앱 안 웹뷰 `.web`, `http`/`https`가 아니면 누를 수 없다). 탭 바는 route가 숨긴다. `originalUrl`이 없으면(첫 로딩·오류)
/// 하단 바를 그리지 않는다. `notice`는 위쪽 바 아래의 짧은 안내 줄이다(C1에 토스트 부품이 없다). `refresh`가 있으면
/// 당겨서 새로고침(시스템 indicator, 홈과 같다)이고 그 작업이 끝날 때까지 indicator가 돈다.
struct DetailScaffold<Content: View, More: View>: View {
    let onBack: () -> Void
    var originalUrl: String?
    var notice: BriefNotice?
    var refresh: (() async -> Void)?
    @ViewBuilder var more: () -> More
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var c

    var body: some View {
        ZStack(alignment: .top) {
            // 밀기 동안 아래 화면이 비치지 않게 자기 바탕을 칠한다.
            c.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    content()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DetailLayout.contentTop)
                .padding(.bottom, originalUrl != nil ? DetailLayout.bottomWithBar : DetailLayout.bottomWithoutBar)
            }
            .ignoresSafeArea(.container, edges: .bottom)
            .modifier(DetailRefreshable(refresh: refresh))
            WLTopBar {
                WLCircleButton(.back, label: String(localized: "detail.back"), action: onBack)
            } center: {
                EmptyView()
            } trailing: {
                more()
            }
            DetailNoticeLine(notice: notice)
                .padding(.top, DetailLayout.noticeTop)
            if let originalUrl {
                OriginalBar(url: originalUrl)
            }
        }
    }
}

private struct DetailRefreshable: ViewModifier {
    let refresh: (() async -> Void)?

    func body(content: Content) -> some View {
        if let refresh {
            content.refreshable { await refresh() }
        } else {
            content
        }
    }
}

/// 하단 고정 "원본 보기": 위 12·좌우 20, 높이 56 pill 반전색, 16/700 + 바깥 링크 18(간격 8). 화면 아래 끝에서 36 위.
private struct OriginalBar: View {
    let url: String

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav

    var body: some View {
        let page = WebPageURL(string: url)
        Button {
            if let page { nav.push(AppDestination.web(page).route, sourceKey: "detail/original") }
        } label: {
            HStack(spacing: WishlistTokens.Space.s8) {
                WLText(String(localized: "detail.open.original"), .button, color: c.onInverse, maxLines: 1)
                WLIcon(.external, size: 18, color: c.onInverse)
            }
            .frame(maxWidth: .infinity, minHeight: DetailLayout.barButton)
            .background(c.text, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(page == nil)
        .opacity(page == nil ? 0.4 : 1)
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .padding(.top, DetailLayout.barTop)
        .padding(.bottom, DetailLayout.barBottom)
        .background(c.background)
        .frame(maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea(.container, edges: .bottom)
    }
}

/// 짧은 안내 줄: 위쪽 바 아래, 카드색 pill 13/400, 나타남·사라짐 opacity 200(구현 기본값). VoiceOver에는 알림으로 읽힌다.
/// 3초 뒤 스스로 사라진다(같은 `serial`은 다시 보이지 않는다).
struct DetailNoticeLine: View {
    let notice: BriefNotice?

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: BriefNotice?

    var body: some View {
        ZStack {
            if let shown {
                WLText(shown.text, DetailStyles.notice, color: c.text, maxLines: 2)
                    .padding(.horizontal, WishlistTokens.Space.s16)
                    .padding(.vertical, 10)
                    .background(c.card, in: Capsule())
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : WishlistTokens.Curve.fadeIn.animation(ms: WishlistTokens.Motion.dialogIn), value: shown)
        .allowsHitTesting(false)
        .onChange(of: notice) { _, next in
            guard let next else { return }
            shown = next
            AccessibilityNotification.Announcement(next.text).post()
        }
        .task(id: shown) {
            guard shown != nil else { return }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled { shown = nil }
        }
    }
}

/// 상태 화면(항목 없는 오류·삭제된 상품): 경고 타일 + 18/700 문장 + 버튼 하나, 본문 영역 가운데.
struct DetailStatusBlock: View {
    let message: String
    let buttonText: String
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s16) {
            WLIconTile(size: 56, radius: WishlistTokens.Radius.m, color: c.card) { WLIcon(.warning, color: c.textSecondary) }
            WLText(message, DetailStyles.status, color: c.text, alignment: .center)
                .wlArrivalFocus()
            WLButton(buttonText, kind: .secondary, action: action)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, WishlistTokens.Space.s32)
        .padding(.vertical, 120)
    }
}

/// 사진 칸: 좌우 20, 정사각, 모서리 20, 카드색.
struct DetailPhotoFrame<Content: View>: View {
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var c

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .overlay { content() }
            .background(c.card, in: shape)
            .clipShape(shape)
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
    }
}

/// 분석 중·로컬 대기 공통 사진 자리: 좌우 20, 높이 222, 모서리 28, 카드색, 가운데 상태 타일 56(모서리 20) + 14 문구(간격 10).
/// 문구가 비면(로딩) 접근성에서 숨긴다.
struct DetailWaitingFrame: View {
    let icon: WLLineIcon
    let tile: DetailTile
    let caption: String

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(spacing: 10) {
            WLIconTile(size: 56, radius: WishlistTokens.Radius.m, color: tile.surface) { WLIcon(icon, size: 28, color: tile.icon) }
            if !caption.isEmpty {
                WLText(caption, .body, color: c.textSecondary, alignment: .center)
                    .padding(.horizontal, WishlistTokens.Space.s16)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: DetailLayout.waitingHeight)
        .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(caption)
        .accessibilityHidden(caption.isEmpty)
    }
}

/// 분석 중·로컬 대기 공통 글자 묶음: 위 24·좌우 20, 22/700 제목(host) + 내용(간격 12).
struct DetailWaitingBody<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            WLText(title, DetailStyles.name)
                .wlArrivalFocus()
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .padding(.top, WishlistTokens.Space.s24)
    }
}

/// "지금"(상대 시각 문구용). 화면이 보이는 동안 1분마다 앞으로 간다.
struct DetailMinuteClock<Content: View>: View {
    @ViewBuilder var content: (Date) -> Content

    @State private var now = Date()

    var body: some View {
        content(now)
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                    now = Date()
                }
            }
    }
}

extension WLNavigator {
    /// Runs a stack change for the screen of `entryID` once no transition is running (`pop` and
    /// `replaceTop` refuse during one, e.g. a `MovedTo` that arrives while the screen still slides in).
    /// Gives up when that entry is no longer the current tab's top (the user left, or the shell dropped it),
    /// or when the calling task is cancelled. Android `WLNavigator.whenSettled`.
    @MainActor
    func whenSettled(entryID: Int, _ change: () -> Bool) async {
        while !Task.isCancelled {
            if isTransitioning {
                try? await Task.sleep(nanoseconds: 30_000_000)
                continue
            }
            guard entries(currentTab).last?.id == entryID else { return }
            if change() { return }
            try? await Task.sleep(nanoseconds: 30_000_000)
        }
    }
}
