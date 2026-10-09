import SwiftUI

private struct WLNavigatorKey: EnvironmentKey {
    static let defaultValue: WLNavigator? = nil
}

extension EnvironmentValues {
    /// 앱 루트가 navigator를 넣는 자리. key path 쓰기는 getter를 먼저 부르므로 넣는 쪽은 optional이어야 한다.
    var wlNavigatorStorage: WLNavigator? {
        get { self[WLNavigatorKey.self] }
        set { self[WLNavigatorKey.self] = newValue }
    }

    /// 화면이 `push`·`pop`을 부르기 위한 접근(읽기 전용). 앱 루트가 넣지 않았으면 바로 실패한다.
    var wlNavigator: WLNavigator {
        guard let navigator = self[WLNavigatorKey.self] else {
            preconditionFailure("Inject wlNavigator above OverlayHost at the app root.")
        }
        return navigator
    }
}

/// 직접 그린 탭 셸. 시스템 `TabView`·`NavigationStack`·옆으로 밀리는 전환을 쓰지 않는다(Android `WLNavHost`와 같은 동작).
///
/// - 세 탭의 모든 스택 칸을 한 `ZStack`에 펼쳐 살려 둔다(칸 id가 정체성). 스크롤·입력 상태와 공유 요소 원래 자리가
///   가려진 동안에도 남아 깊이 2 이상에서도 push·pop·끌어서 뒤로가 같다.
/// - 탭 전환: 페이드 스루(이전 90 ease-in → 새 탭 210 fade-in + scale .97, 90 지연). 탭 바는 하나이고 늘 제자리다.
/// - 화면 이동: 사진이 있는 이동은 `WLNavMotion`이 사진을 phase로 보간해 날린다. 사진이 없는 이동은 가로 밀기다
///   (새 화면 x W → 0, 아래 화면 0 → −parallax·W, 같은 phase). 탭 바는 탭 바가 보이는 가장 위 칸 바로 위에 놓인다.
///   두 화면 모두 탭 바를 보이면 탭 바는 움직이지도 옅어지지도 않고, 보임이 다르면 진행값과 함께 옅어진다.
/// - 뒤로: 화면의 뒤로 버튼·VoiceOver escape는 `pop()`, 왼쪽 가장자리 끌기는 `WLEdgeBackGesture`(window 수준 UIKit pan).
/// - 전환 중에는 화면 전체 입력을 막고 navigator도 호출을 무시한다.
/// - 접근성: 가려진 칸·다른 탭·숨은 탭 바는 층마다 `wlAccessibilityCovered`로 뺀다. `.isModal`은 쓰지 않는다
///   (탭 바를 유지하는 화면에서 탭 바까지 빠진다).
struct WLNavHost<Content: View>: View {
    let navigator: WLNavigator
    let motion: WLNavMotion
    private let content: (WLRoute) -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.wlColors) private var c
    @Environment(\.overlayHostState) private var overlay

    init(navigator: WLNavigator, motion: WLNavMotion, @ViewBuilder content: @escaping (WLRoute) -> Content) {
        self.navigator = navigator
        self.motion = motion
        self.content = content
    }

    var body: some View {
        let layers = makeLayers()
        ZStack {
            c.background.ignoresSafeArea()
            ForEach(layers) { layer in
                WLEntryLayer(layer: layer, motion: motion, navigator: navigator, content: content)
                    .zIndex(layer.z)
            }
            let current = displayed(navigator.currentTab)
            WLTabBarSlot(navigator: navigator, motion: motion, top: current.last, below: current.dropLast().last)
                .zIndex(tabBarZ())
            // 끌어서 뒤로·되돌림 중에도 막는다(두 번째 손가락·누르기가 상세에 닿지 않게). 끌기 자체는 window 인식기가 받으므로
            // 이 막과 상관없다.
            if navigator.isTransitioning {
                InputBlocker().zIndex(Double(Int32.max))
            }
        }
        .environment(\.wlNavigatorStorage, navigator)
        .environment(\.wlNavMotionStorage, motion)
        .background {
            WLEdgeBackGesture(
                canBegin: { [motion, overlay] in motion.canBeginDrag && (overlay?.entries.isEmpty ?? true) },
                onBegin: { [motion] in motion.beginDrag() },
                onChange: { [motion] in motion.updateDrag($0) },
                onEnd: { [motion] in motion.endDrag(progress: $0, velocity: $1) }
            )
        }
        .onChange(of: reduceMotion, initial: true) { _, value in motion.reduceMotion = value }
        .onChange(of: navigator.activeTransition) { _, t in motion.handle(t) }
    }

    /// 탭에 그려지는 칸들: 스택 + 뒤로 모션 중인 칸.
    private func displayed(_ tab: WLTab) -> [WLBackStackEntry] {
        var list = navigator.entries(tab)
        if let ex = navigator.exiting, ex.tab == tab { list.append(ex.entry) }
        return list
    }

    private static func z(current: Bool, index: Int) -> Double { (current ? 1000 : 0) + Double(index * 2) }

    private func makeLayers() -> [WLEntryLayerModel] {
        WLTab.allCases.flatMap { tab -> [WLEntryLayerModel] in
            let list = displayed(tab)
            let isCurrent = tab == navigator.currentTab
            // replace 중 위에 그려지는 바뀐 칸은 새 칸을 밀지 않는다(둘 다 제자리에서 옅어진다).
            let replaced = navigator.activeTransition?.kind == .replace ? navigator.exiting?.entry.id : nil
            return list.enumerated().map { index, entry in
                let above = index + 1 < list.count && list[index + 1].id != replaced ? list[index + 1] : nil
                return WLEntryLayerModel(
                    entry: entry,
                    tab: tab,
                    isFront: isCurrent && index == list.count - 1,
                    z: Self.z(current: isCurrent, index: index),
                    slidingAbove: above.flatMap { $0.route.pushStyle == .slide ? $0.id : nil }
                )
            }
        }
    }

    /// 탭 바는 지금 탭에서 탭 바가 보이는 가장 위 칸 바로 위.
    private func tabBarZ() -> Double {
        let list = displayed(navigator.currentTab)
        let index = list.lastIndex { $0.route.showsTabBar } ?? 0
        return Self.z(current: true, index: index) + 1
    }
}

struct WLEntryLayerModel: Identifiable {
    let entry: WLBackStackEntry
    let tab: WLTab
    /// 지금 탭의 맨 위(보이고 누를 수 있는) 칸.
    let isFront: Bool
    let z: Double
    /// 바로 위 칸이 밀기로 열린 칸이면 그 id. 이 칸은 그 칸의 phase만큼 왼쪽으로 조금 밀린다(parallax).
    let slidingAbove: Int?

    var id: Int { entry.id }
}

/// 스택 칸 하나. 탭 첫 화면은 그대로, 사진 상세는 날아가는 사진(위)과 함께, 밀기 상세는 phase만큼 오른쪽에서 밀어 그린다.
private struct WLEntryLayer<Content: View>: View {
    let layer: WLEntryLayerModel
    let motion: WLNavMotion
    let navigator: WLNavigator
    let content: (WLRoute) -> Content

    var body: some View {
        let entry = layer.entry
        let isRoot = entry.route.isTabRoot
        ZStack {
            // 자식이 하나뿐인 ZStack에 건 `.contain` 컨테이너는 접근성 트리에서 접혀(그 자식에 합쳐져) 칸의 escape 동작이
            // VoiceOver 전달 경로에서 빠졌다(Task 7, serve-sim escape 흉내). 늘 둘 이상이 되도록 보이지 않는 자리를 둔다.
            Color.clear
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            if isRoot {
                content(entry.route)
            } else {
                let ch = motion.channels(entry.id)
                switch entry.route.pushStyle {
                case .photo:
                    WLContentFade(channels: ch) { content(entry.route) }
                    WLPhotoFlightLayer(channels: ch, photo: motion.registry.photo(entry.sourceKey))
                case .slide:
                    content(entry.route)
                }
            }
        }
        .modifier(WLEntryFade(channels: isRoot ? nil : motion.channels(entry.id)))
        .environment(\.wlEntryID, entry.id)
        // 밀기: 이 칸이 밀기로 열렸으면 x = (1 − phase)·W, 바로 위 칸이 밀기면 x = −parallax·W·phase(위 칸).
        .modifier(WLSlideOffset(
            own: !isRoot && entry.route.pushStyle == .slide ? motion.channels(entry.id) : nil,
            above: layer.slidingAbove.map { motion.channels($0) }
        ))
        .modifier(WLTabFade(channels: motion.tab(layer.tab)))
        .allowsHitTesting(layer.isFront)
        // 가려진 칸·다른 탭은 층마다 뺀다. 맨 위 칸만 escape(두 손가락 문지르기)로 뒤로 간다.
        .wlAccessibilityCovered(!layer.isFront)
        .modifier(WLEscapeBack(enabled: !isRoot, navigator: navigator))
    }
}

private struct WLEscapeBack: ViewModifier {
    let enabled: Bool
    let navigator: WLNavigator

    func body(content: Content) -> some View {
        // `enabled`는 칸마다 고정(탭 첫 화면인지)이라 분기해도 칸의 정체성이 바뀌지 않는다.
        if enabled {
            content.accessibilityAction(.escape) { navigator.pop() }
        } else {
            content
        }
    }
}

private struct WLTabFade: ViewModifier {
    let channels: WLTabChannels

    func body(content: Content) -> some View {
        content
            .opacity(channels.opacity)
            .scaleEffect(channels.scale)
    }
}

/// replace cross-fade(칸 전체). 분기하지 않는다(칸의 정체성이 바뀌면 스크롤·입력 상태가 사라진다).
private struct WLEntryFade: ViewModifier {
    let channels: WLEntryChannels?

    func body(content: Content) -> some View {
        content.opacity(channels?.fade ?? 1)
    }
}

private struct WLContentFade<Inner: View>: View {
    let channels: WLEntryChannels
    @ViewBuilder let inner: () -> Inner

    var body: some View {
        inner().opacity(channels.content)
    }
}

private struct WLPhotoFlightLayer: View {
    let channels: WLEntryChannels
    let photo: AnyView?

    var body: some View {
        if channels.animating {
            WLPhotoFlight(phase: channels.phase, source: channels.source, target: channels.target, photo: photo)
        }
    }
}

/// 밀기 위치. 화면 폭은 이 칸의 폭이다(칸은 화면 전체를 덮는다). 렌더 단계(`visualEffect`)라 레이아웃을 다시 하지 않는다.
private struct WLSlideOffset: ViewModifier {
    let own: WLEntryChannels?
    let above: WLEntryChannels?

    func body(content: Content) -> some View {
        let x = (own.map { 1 - $0.phase } ?? 0) - (above.map { WishlistTokens.Motion.pushSlideParallax * $0.phase } ?? 0)
        // 분기하지 않는다(칸의 정체성이 바뀌면 스크롤·입력 상태가 사라진다).
        content.visualEffect { view, geometry in
            view.offset(x: geometry.size.width * x)
        }
    }
}

/// 탭 바 자리. 맨 위 칸이 탭 바를 보이지 않는 화면이면 그 화면 내용과 함께 옅어지고, 다 가려지면 누를 수 없고 접근성에서도 빠진다.
private struct WLTabBarSlot: View {
    let navigator: WLNavigator
    let motion: WLNavMotion
    let top: WLBackStackEntry?
    let below: WLBackStackEntry?

    var body: some View {
        let shows = top?.route.showsTabBar ?? true
        // 밀기로 열린 맨 위 화면이 탭 바를 보이고 아래 화면은 안 보이면 진행값과 함께 나타난다.
        let fadesIn = shows && top.map { !$0.route.isTabRoot && $0.route.pushStyle == .slide } == true
            && below.map { !$0.route.showsTabBar } == true
        WLTabBar(current: navigator.currentTab) { navigator.selectTab($0) }
            .modifier(WLTabBarAlpha(shows: shows, fadesIn: fadesIn, channels: top.map { motion.channels($0.id) }))
            .frame(maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea(.all, edges: .bottom)
            .allowsHitTesting(shows)
            .wlAccessibilityCovered(!shows)
    }
}

private struct WLTabBarAlpha: ViewModifier {
    let shows: Bool
    let fadesIn: Bool
    let channels: WLEntryChannels?

    func body(content: Content) -> some View {
        let progress = channels?.content ?? 1
        content.opacity(fadesIn ? progress : (shows ? 1 : 1 - progress))
    }
}

// MARK: - 화면이 쓰는 도움

/// push가 끝나면 VoiceOver 포커스를 이 요소(상세 제목)로 옮긴다. VoiceOver가 꺼져 있으면 아무 일도 하지 않는다.
private struct WLArrivalFocus: ViewModifier {
    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlEntryID) private var entryID
    @AccessibilityFocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .accessibilityAddTraits(.isHeader)
            .accessibilityFocused($focused)
            .onChange(of: motion.arrival) { _, arrival in
                if let arrival, arrival.entryID == entryID { focused = true }
            }
    }
}

extension View {
    /// 상세 화면 제목에 붙인다(머리 표시 + push 뒤 VoiceOver 포커스).
    func wlArrivalFocus() -> some View { modifier(WLArrivalFocus()) }
}

/// 탭 첫 화면의 세로 스크롤. 현재 탭을 다시 누르면 맨 위로 부드럽게 스크롤한다(motion.md 머리 접기의 부드러운 스크롤 값).
struct WLTabScrollView<Content: View>: View {
    let tab: WLTab
    @ViewBuilder let content: () -> Content

    @Environment(\.wlNavigator) private var navigator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let topID = "wl-scroll-top"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 0).id(topID)
                    content()
                    // 탭 바가 내용 끝을 가리지 않게(탭 바는 화면 아래 끝에서 잰다).
                    Color.clear.frame(height: wlTabBarHeight + wlTabBarBottomGap + WishlistTokens.Space.s16)
                }
            }
            .ignoresSafeArea(.container, edges: .bottom)
            .onChange(of: navigator.scrollToTopRequest) { _, request in
                guard request == tab else { return }
                withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll)) {
                    proxy.scrollTo(topID, anchor: .top)
                }
                navigator.consumeScrollToTop(tab)
            }
        }
    }
}
