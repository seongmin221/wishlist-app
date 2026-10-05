#if DEBUG
import Observation
import SwiftUI
import UIKit

// iOS 라우터 spike (C1 Task 5). Task 7에서 실제 라우터로 대체하면서 이 폴더와 ContentView의 `-RouterSpike` 진입점을 지운다.
// 질문: NavigationStack 없이 탭별 [route] 상태 + ZStack 오버레이로
// (a) 사진·면 공유 요소 push/pop, (b) 왼쪽 가장자리 끌어 뒤로, (c) VoiceOver 포커스·escape, (d) 탭별 스택 유지를 만들 수 있는가.
//
// 공유 요소는 matchedGeometryEffect 대신 "원래 자리 사각형 ↔ 상세 자리 사각형"을 phase 값 하나로 보간해 그린다.
// matchedGeometryEffect는 상태 전환 한 번을 애니메이션 한 번으로만 움직여 손가락 진행값으로 되감을 수 없기 때문이다.
// phase: 사진 0(카드 자리) → 1(상세 자리). 면 0(원래 요소) → 1(떠오름) → 2(화면 전체).

enum SpikeTab: String, CaseIterable, Identifiable {
    case home = "홈"
    case category = "카테고리"
    var id: String { rawValue }
}

enum SpikeStyle { case photo, surface }

struct SpikeEntry: Identifiable, Equatable {
    let id: Int
    let style: SpikeStyle
    let sourceKey: String
    let title: String
    /// 사진 상세는 탭 바 없는 화면, 칩 → 목록은 탭 바를 유지하는 화면으로 둔다(탭별 스택 유지 확인용).
    var showsTabBar: Bool { style == .surface }
}

struct SpikeTabState {
    var stack: [SpikeEntry] = []
    /// 맨 위 화면의 공유 요소 phase.
    var phase: Double = 0
    /// 맨 위 화면 내용 불투명도.
    var content: Double = 0
}

@Observable
final class SpikeRouter {
    var selected: SpikeTab = .home
    var tabs: [SpikeTab: SpikeTabState] = [.home: SpikeTabState(), .category: SpikeTabState()]
    var tabOpacity: [SpikeTab: Double] = [.home: 1, .category: 0]
    var tabScale: [SpikeTab: Double] = [.home: 1, .category: 1]
    var isTransitioning = false
    /// 마지막 push가 끝난 횟수. 상세가 이 값을 보고 VoiceOver 포커스를 옮긴다.
    var settledPushCount = 0
    var isDraggingBack = false
    private var nextId = 1

    private typealias C = WishlistTokens.Curve
    private typealias M = WishlistTokens.Motion

    func state(_ tab: SpikeTab) -> SpikeTabState { tabs[tab] ?? SpikeTabState() }
    var current: SpikeTabState { state(selected) }

    private func mutate(_ tab: SpikeTab, _ change: (inout SpikeTabState) -> Void) {
        var s = state(tab)
        change(&s)
        tabs[tab] = s
    }

    private func snap(_ change: () -> Void) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t, change)
    }

    // MARK: 탭

    func select(_ tab: SpikeTab) {
        guard !isTransitioning, tab != selected else { return }
        let old = selected
        isTransitioning = true
        snap { tabScale[tab] = M.tabIncomingScale }
        selected = tab
        withAnimation(C.easeIn.animation(ms: M.tabOutgoing)) { tabOpacity[old] = 0 }
        withAnimation(C.fadeIn.animation(ms: M.tabIncoming).delay(Double(M.tabIncomingDelay) / 1000)) {
            tabOpacity[tab] = 1
            tabScale[tab] = 1
        } completion: { [weak self] in
            self?.isTransitioning = false
        }
    }

    // MARK: push / pop

    func push(style: SpikeStyle, sourceKey: String, title: String) {
        guard !isTransitioning else { return }
        let tab = selected
        let entry = SpikeEntry(id: nextId, style: style, sourceKey: sourceKey, title: title)
        nextId += 1
        isTransitioning = true
        snap {
            mutate(tab) {
                $0.stack.append(entry)
                $0.phase = 0
                $0.content = 0
            }
        }
        switch style {
        case .photo:
            withAnimation(C.emphasized.animation(ms: M.pushPhotoOpen)) {
                mutate(tab) { $0.phase = 1 }
            } completion: { [weak self] in self?.finishPush() }
            withAnimation(C.easeOut.animation(ms: M.pushPhotoContent)) {
                mutate(tab) { $0.content = 1 }
            }
        case .surface:
            withAnimation(C.easeOut.animation(ms: M.pushSurfaceLift)) {
                mutate(tab) { $0.phase = 1 }
            } completion: { [weak self] in
                withAnimation(C.emphasized.animation(ms: M.pushSurfaceExpand)) {
                    self?.mutate(tab) { $0.phase = 2 }
                } completion: { [weak self] in self?.finishPush() }
            }
            let delay = Double(M.pushSurfaceLift + M.pushSurfaceContentDelay) / 1000
            withAnimation(C.easeOut.animation(ms: M.pushSurfaceContent).delay(delay)) {
                mutate(tab) { $0.content = 1 }
            }
        }
    }

    private func finishPush() {
        isTransitioning = false
        settledPushCount += 1
        if ProcessInfo.processInfo.arguments.contains("-SpikeEmulateEscape") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { SpikeEscapeProbe.run() }
        }
    }

    func pop() {
        guard !isTransitioning, let top = current.stack.last else { return }
        isTransitioning = true
        runPop(tab: selected, style: top.style, scale: 1)
    }

    /// scale: 남은 거리 비율(끌어서 뒤로 확정 시 1보다 작다).
    private func runPop(tab: SpikeTab, style: SpikeStyle, scale: Double) {
        let finish: () -> Void = { [weak self] in
            guard let self else { return }
            snap { self.mutate(tab) { _ = $0.stack.popLast(); $0.phase = $0.stack.isEmpty ? 0 : (style == .photo ? 1 : 2); $0.content = $0.stack.isEmpty ? 0 : 1 } }
            isTransitioning = false
        }
        switch style {
        case .photo:
            withAnimation(C.emphasized.animation(ms: scaled(M.pushPhotoBack, scale))) {
                mutate(tab) { $0.phase = 0 }
            } completion: { finish() }
            withAnimation(C.easeIn.animation(ms: scaled(M.pushPhotoBackContent, scale))) {
                mutate(tab) { $0.content = 0 }
            }
        case .surface:
            withAnimation(C.easeIn.animation(ms: scaled(M.pushSurfaceBackContent, scale))) {
                mutate(tab) { $0.content = 0 }
            }
            withAnimation(C.emphasized.animation(ms: scaled(M.pushSurfaceBack, scale))) {
                mutate(tab) { $0.phase = 1 }
            } completion: { [weak self] in
                withAnimation(C.easeIn.animation(ms: M.pushSurfaceSettle)) {
                    self?.mutate(tab) { $0.phase = 0 }
                } completion: { finish() }
            }
        }
    }

    private func scaled(_ ms: Int, _ scale: Double) -> Int {
        // 남은 거리만큼만 움직인다. 너무 짧으면 끊겨 보여 하한 120ms(Android 구현과 같은 값)를 둔다.
        max(120, Int(Double(ms) * min(1, max(0, scale))))
    }

    // MARK: 끌어서 뒤로 (진행값 0~1)

    func beginBackDrag() -> Bool {
        guard !isTransitioning, !current.stack.isEmpty else { return false }
        isTransitioning = true
        isDraggingBack = true
        return true
    }

    func updateBackDrag(_ progress: Double) {
        guard isDraggingBack, let top = current.stack.last else { return }
        let p = min(1, max(0, progress))
        snap {
            mutate(selected) {
                // 사진: 상세 자리(1) → 카드 자리(0). 면: 화면 전체(2) → 떠오름(1).
                $0.phase = top.style == .photo ? 1 - p : 2 - p
                $0.content = 1 - p
            }
        }
    }

    func endBackDrag(progress: Double, velocity: Double) {
        guard isDraggingBack, let top = current.stack.last else { return }
        isDraggingBack = false
        let p = min(1, max(0, progress))
        // 빠르게 놓음: 진행 속도 1.5/s 이상(Android 구현과 같은 기준). 디자인 값은 commit 50%만 정한다.
        let commit = p >= M.interactiveBackCommitProgress || velocity >= 1.5
        let tab = selected
        if commit {
            runPop(tab: tab, style: top.style, scale: 1 - p)
        } else {
            let full: Double = top.style == .photo ? 1 : 2
            withAnimation(C.emphasized.animation(ms: scaled(M.pushPhotoBack, p))) {
                mutate(tab) {
                    $0.phase = full
                    $0.content = 1
                }
            } completion: { [weak self] in self?.isTransitioning = false }
        }
    }
}

// MARK: - escape 검증 보조 (시뮬레이터에 VoiceOver가 없어 UIKit 접근성 계층에서 escape 전달을 흉내 낸다)

/// VoiceOver의 두 손가락 문지르기는 초점 요소에 accessibilityPerformEscape()를 보내고, false면 accessibilityContainer를
/// 따라 올라가며 같은 호출을 반복한다. 여기서는 상세 안의 글자 요소("… 탭 상세 #n")를 초점 요소로 보고 같은 순서로 보낸다.
enum SpikeEscapeProbe {
    static func run() {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return log("no window") }
        guard let leaf = find(in: window, depth: 0) else { return log("detail label not found") }
        var node: NSObject? = leaf
        var hops = 0
        while let current = node {
            log("hop \(hops): \(type(of: current)) label=\(current.accessibilityLabel ?? "-")")
            if current.accessibilityPerformEscape() { return log("escape handled after \(hops) hops by \(type(of: current))") }
            hops += 1
            node = container(of: current)
        }
        log("escape not handled (\(hops) hops)")
    }

    private static func container(of object: NSObject) -> NSObject? {
        if let view = object as? UIView { return view.superview }
        let selector = NSSelectorFromString("accessibilityContainer")
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue() as? NSObject
    }

    private static func find(in object: NSObject, depth: Int) -> NSObject? {
        if depth > 40 { return nil }
        if let label = object.accessibilityLabel, label.contains("탭 상세 #"), !object.accessibilityElementsHidden { return object }
        var children: [NSObject] = []
        if let elements = object.accessibilityElements as? [NSObject] { children += elements }
        let count = object.accessibilityElementCount()
        if count != NSNotFound, count > 0 {
            for i in 0..<count { if let e = object.accessibilityElement(at: i) as? NSObject { children.append(e) } }
        }
        if let view = object as? UIView { children += view.subviews }
        for child in children { if let hit = find(in: child, depth: depth + 1) { return hit } }
        return nil
    }

    private static func log(_ message: String) {
        FileHandle.standardError.write(Data("SPIKE escape probe: \(message)\n".utf8))
    }
}

// MARK: - 프레임 수집

private struct SpikeFrameKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

private extension View {
    func spikeFrame(_ key: String) -> some View {
        background(GeometryReader { proxy in
            Color.clear.preference(key: SpikeFrameKey.self, value: [key: proxy.frame(in: .global)])
        })
    }
}

// MARK: - 화면

struct RouterSpikeView: View {
    @Environment(\.colorScheme) private var scheme
    @State private var router = SpikeRouter()
    @State private var frames: [String: CGRect] = [:]

    private var colors: WLColors { scheme == .dark ? .dark : .light }

    var body: some View {
        ZStack(alignment: .bottom) {
            colors.background.ignoresSafeArea()
            ForEach(SpikeTab.allCases) { tab in
                // 주의: 조상에 .accessibilityHidden(false)를 두면 자손의 .accessibilityHidden(true)가 무시된다(spike에서 확인).
                // 그래서 숨김은 가려지는 잎 층마다 따로 건다.
                SpikeTabStackView(tab: tab, isActive: tab == router.selected, router: router, frames: frames, colors: colors)
                    .opacity(router.tabOpacity[tab] ?? 0)
                    .scaleEffect(router.tabScale[tab] ?? 1)
                    .allowsHitTesting(tab == router.selected)
                    .zIndex(tab == router.selected ? 1 : 0)
            }
            let barVisible = router.current.stack.last?.showsTabBar ?? true
            let chrome = barVisible ? 1 : 1 - router.current.content
            SpikeTabBar(router: router, colors: colors)
                .opacity(chrome)
                .allowsHitTesting(barVisible)
                .accessibilityHidden(!barVisible)
                .zIndex(2)
        }
        // 전환 중 입력 차단(motion.md 구현 기본값). 끌어서 뒤로 중에는 끄면 제스처가 끊기므로 예외.
        .allowsHitTesting(!router.isTransitioning || router.isDraggingBack)
        .onPreferenceChange(SpikeFrameKey.self) { frames = $0 }
        .background {
            if SpikeEdgeMode.current == .uiKitEdgePan { SpikeEdgePanInstaller(router: router) }
        }
    }
}

private struct SpikeTabStackView: View {
    let tab: SpikeTab
    let isActive: Bool
    let router: SpikeRouter
    let frames: [String: CGRect]
    let colors: WLColors

    var body: some View {
        let state = router.state(tab)
        let top = state.stack.last
        ZStack {
            SpikeTabRoot(tab: tab, router: router, colors: colors, hiddenPhotoKey: top?.style == .photo ? top?.sourceKey : nil)
                .accessibilityHidden(!isActive || top != nil)
            if let top {
                switch top.style {
                case .photo:
                    SpikeDetailView(entry: top, tab: tab, router: router, colors: colors, drawsBackground: true)
                        .opacity(state.content)
                        .accessibilityHidden(!isActive)
                    SpikeSharedPhoto(phase: state.phase,
                                     source: frames[top.sourceKey] ?? .zero,
                                     target: frames["detail-photo-\(tab.rawValue)"] ?? .zero,
                                     colors: colors)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                case .surface:
                    SpikeSharedSurface(phase: state.phase,
                                       source: frames[top.sourceKey] ?? .zero,
                                       sourceColor: colors.chip,
                                       targetColor: colors.background)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                    SpikeDetailView(entry: top, tab: tab, router: router, colors: colors, drawsBackground: false)
                        .opacity(state.content)
                        .accessibilityHidden(!isActive)
                }
                if SpikeEdgeMode.current == .swiftUIStrip { SpikeEdgeBackStrip(router: router) }
            }
        }
    }
}

// MARK: 공유 요소 그리기 (phase 보간)

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: Double) -> CGFloat { a + (b - a) * CGFloat(t) }
private func lerp(_ a: CGRect, _ b: CGRect, _ t: Double) -> CGRect {
    CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t), width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
}

private struct SpikeSharedPhoto: View, Animatable {
    var phase: Double
    let source: CGRect
    let target: CGRect
    let colors: WLColors

    var animatableData: Double {
        get { phase }
        set { phase = newValue }
    }

    var body: some View {
        let r = lerp(source, target, phase)
        SpikePhoto(colors: colors)
            .frame(width: r.width, height: r.height)
            .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
            .offset(x: r.minX, y: r.minY)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .ignoresSafeArea()
    }
}

private struct SpikeSharedSurface: View, Animatable {
    var phase: Double
    let source: CGRect
    let sourceColor: Color
    let targetColor: Color

    var animatableData: Double {
        get { phase }
        set { phase = newValue }
    }

    var body: some View {
        GeometryReader { proxy in
            let screen = CGRect(origin: .zero, size: proxy.size)
            let outset = CGFloat(WishlistTokens.Motion.pushSurfaceLiftOutset)
            let lifted = source.insetBy(dx: -outset, dy: -outset)
            let lift = min(1, phase)
            let expand = max(0, phase - 1)
            let rect = phase <= 1 ? lerp(source, lifted, lift) : lerp(lifted, screen, expand)
            // 칩은 알약 모양이라 시작 모서리 = 높이의 절반. 화면 전체로 커지며 0이 된다.
            let radius = lerp(lifted.height / 2, 0, expand)
            // 그림자: 떠오르며 생기고 커지며 사라진다.
            let shadow = lift * (1 - expand)
            RoundedRectangle(cornerRadius: min(radius, rect.height / 2), style: .continuous)
                .fill(mix(sourceColor, targetColor, expand))
                .shadow(color: .black.opacity(WishlistTokens.Motion.pushSurfaceLiftShadowAlpha * shadow),
                        radius: WishlistTokens.Motion.pushSurfaceLiftShadowBlur / 2,
                        y: WishlistTokens.Motion.pushSurfaceLiftShadowY * shadow)
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
        }
        .ignoresSafeArea()
    }

    private func mix(_ a: Color, _ b: Color, _ t: Double) -> Color {
        let ra = UIColor(a).cgColor.components ?? [0, 0, 0, 1]
        let rb = UIColor(b).cgColor.components ?? [0, 0, 0, 1]
        func c(_ i: Int) -> Double { Double(lerp(ra[min(i, ra.count - 1)], rb[min(i, rb.count - 1)], t)) }
        return Color(.sRGB, red: c(0), green: c(1), blue: c(2), opacity: 1)
    }
}

private struct SpikePhoto: View {
    let colors: WLColors
    var body: some View {
        ZStack {
            WishlistTokens.Purpose.periwinkle
            Image(systemName: "headphones")
                .font(.system(size: 64, weight: .light))
                .foregroundStyle(WishlistTokens.Purpose.onPurpose)
        }
    }
}

// MARK: 끌어서 뒤로

/// 두 방식을 비교했다. 기본은 UIKit 가장자리 인식기, `-SpikeEdgeSwiftUI` 실행 인자로 SwiftUI 띠 방식.
enum SpikeEdgeMode {
    case uiKitEdgePan, swiftUIStrip
    static let current: SpikeEdgeMode =
        ProcessInfo.processInfo.arguments.contains("-SpikeEdgeSwiftUI") ? .swiftUIStrip : .uiKitEdgePan
}

/// 방식 B: window에 UIPanGestureRecognizer를 달고 왼쪽 20pt 안에서 시작한 가로 끌기만 받는다.
/// UINavigationController의 interactivePopGesture와 같은 자리(window 수준 UIKit 인식기)다. 스크롤 뷰의 pan이 이 인식기의
/// 실패를 기다리게 해서, 가장자리에서 시작한 가로 끌기는 가로 스크롤보다 뒤로가 먼저다.
/// UIScreenEdgePanGestureRecognizer는 serve-sim이 넣는 터치로는 한 번도 시작되지 않아(가장자리 정보 없음) 검증할 수 없어서
/// 시작 위치를 직접 보는 일반 pan을 쓴다.
private struct SpikeEdgePanInstaller: UIViewRepresentable {
    let router: SpikeRouter

    func makeCoordinator() -> Coordinator { Coordinator(router: router) }

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.isUserInteractionEnabled = false
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ uiView: InstallerView, context: Context) {}

    final class InstallerView: UIView {
        weak var coordinator: Coordinator?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, let coordinator, coordinator.recognizer.view !== window else { return }
            window.addGestureRecognizer(coordinator.recognizer)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let router: SpikeRouter
        let recognizer = UIPanGestureRecognizer()
        private var active = false
        private let edgeWidth = WishlistTokens.Space.s20

        init(router: SpikeRouter) {
            self.router = router
            super.init()
            recognizer.delegate = self
            recognizer.cancelsTouchesInView = true
            recognizer.addTarget(self, action: #selector(handle(_:)))
        }

        @objc private func handle(_ g: UIPanGestureRecognizer) {
            guard let view = g.view else { return }
            let width = max(1, view.bounds.width)
            let progress = g.translation(in: view).x / width
            switch g.state {
            case .began:
                active = router.beginBackDrag()
                if active { router.updateBackDrag(progress) }
            case .changed:
                if active { router.updateBackDrag(progress) }
            case .ended:
                if active { router.endBackDrag(progress: progress, velocity: g.velocity(in: view).x / width) }
                active = false
            case .cancelled, .failed:
                if active { router.endBackDrag(progress: 0, velocity: 0) }
                active = false
            default:
                break
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard !router.isTransitioning, !router.current.stack.isEmpty, let view = g.view else { return false }
            return touch.location(in: view).x <= edgeWidth
        }

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let pan = g as? UIPanGestureRecognizer, let view = pan.view else { return false }
            let v = pan.velocity(in: view)
            return v.x > abs(v.y)
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            other.view is UIScrollView
        }
    }
}

/// 방식 A: 상세 맨 위에 왼쪽 20pt 띠를 두고 SwiftUI DragGesture로 진행값을 만든다.

private struct SpikeEdgeBackStrip: View {
    let router: SpikeRouter
    @State private var active = false

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            Color.clear
                .contentShape(Rectangle())
                .frame(width: WishlistTokens.Space.s20)
                .frame(maxHeight: .infinity)
                .gesture(
                    DragGesture(minimumDistance: 8, coordinateSpace: .global)
                        .onChanged { value in
                            if !active {
                                // 가로가 우세한 끌기만 뒤로로 받는다.
                                guard value.translation.width > abs(value.translation.height) else { return }
                                active = router.beginBackDrag()
                            }
                            if active { router.updateBackDrag(value.translation.width / width) }
                        }
                        .onEnded { value in
                            guard active else { return }
                            active = false
                            router.endBackDrag(progress: value.translation.width / width,
                                               velocity: value.velocity.width / width)
                        }
                )
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

// MARK: 탭 루트

private struct SpikeTabRoot: View {
    let tab: SpikeTab
    let router: SpikeRouter
    let colors: WLColors
    let hiddenPhotoKey: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s16) {
                Text("\(tab.rawValue) 탭")
                    .wlText(.display28)
                    .foregroundStyle(colors.text)
                    .accessibilityAddTraits(.isHeader)
                ForEach(0..<6) { i in
                    Text("자리 채움 줄 \(i + 1) — 스크롤 위치 확인용")
                        .wlText(.body)
                        .foregroundStyle(colors.textSecondary)
                }
                if tab == .home { photoCard }
                chip
                ForEach(6..<20) { i in
                    Text("자리 채움 줄 \(i + 1)")
                        .wlText(.body)
                        .foregroundStyle(colors.textSecondary)
                }
            }
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
            .padding(.top, WishlistTokens.Space.s24)
            .padding(.bottom, 120)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(colors.background.ignoresSafeArea())
    }

    private var photoKey: String { "photo-\(tab.rawValue)" }
    private var chipKey: String { "chip-\(tab.rawValue)" }

    private var photoCard: some View {
        Button {
            router.push(style: .photo, sourceKey: photoKey, title: "무선 헤드폰")
        } label: {
            HStack(spacing: WishlistTokens.Space.s12) {
                SpikePhoto(colors: colors)
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                    .opacity(hiddenPhotoKey == photoKey ? 0 : 1)
                    .spikeFrame(photoKey)
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                    Text("무선 헤드폰").wlText(.bodyStrong).foregroundStyle(colors.text)
                    Text("329,000원").wlText(.price).foregroundStyle(colors.text)
                }
                Spacer(minLength: 0)
            }
            .padding(WishlistTokens.Space.s12)
            .background(colors.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("무선 헤드폰, 329,000원")
    }

    private var chip: some View {
        Button {
            router.push(style: .surface, sourceKey: chipKey, title: "\(tab.rawValue) 칩 목록")
        } label: {
            Text("캠핑 의자")
                .wlText(.bodyStrong)
                .foregroundStyle(colors.text)
                .padding(.horizontal, WishlistTokens.Space.s16)
                .frame(minHeight: WishlistTokens.Space.minTouch)
                .background(colors.chip, in: Capsule())
                .overlay(Capsule().stroke(colors.line, lineWidth: 1))
                .spikeFrame(chipKey)
        }
        .buttonStyle(.plain)
    }
}

// MARK: 상세

private struct SpikeDetailView: View {
    let entry: SpikeEntry
    let tab: SpikeTab
    let router: SpikeRouter
    let colors: WLColors
    let drawsBackground: Bool
    @AccessibilityFocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s16) {
            Button { router.pop() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(colors.text)
                    .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                    .background(colors.card, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("뒤로")

            if entry.style == .photo {
                // 공유 사진은 라우터 오버레이가 그린다. 여기는 자리와 크기만 잡는다.
                Color.clear
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .spikeFrame("detail-photo-\(tab.rawValue)")
            }
            Text(entry.title)
                .wlText(.title)
                .foregroundStyle(colors.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($titleFocused)
            Text("\(tab.rawValue) 탭 상세 #\(entry.id)")
                .wlText(.label)
                .foregroundStyle(colors.textSecondary)
            // 가장자리 끌기와 가로 스크롤 충돌 확인용.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: WishlistTokens.Space.s8) {
                    ForEach(0..<10) { i in
                        Text("가로 칩 \(i + 1)")
                            .wlText(.label)
                            .foregroundStyle(colors.text)
                            .padding(.horizontal, WishlistTokens.Space.s12)
                            .frame(minHeight: WishlistTokens.Space.minTouch)
                            .background(colors.chip, in: Capsule())
                    }
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
            }
            .padding(.horizontal, -WishlistTokens.Space.screenMargin)
            .accessibilityLabel("가로 칩 목록")
            // 가장자리 끌기와 세로 스크롤 충돌 확인용. 화면 끝까지 닿게 좌우 여백을 상쇄한다.
            ScrollView {
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
                    ForEach(0..<30) { i in
                        Text("세로 스크롤 줄 \(i + 1)")
                            .wlText(.body)
                            .foregroundStyle(colors.textSecondary)
                    }
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, -WishlistTokens.Space.screenMargin)
            .accessibilityLabel("세로 스크롤 영역")
        }
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .padding(.top, WishlistTokens.Space.s8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            if drawsBackground { colors.background.ignoresSafeArea() }
        }
        // 가려진 화면은 라우터가 accessibilityHidden으로 뺀다. .isModal은 탭 바까지 가려 탭 바를 유지하는 화면에서 쓰지 않는다.
        .accessibilityElement(children: .contain)
        .accessibilityAction(.escape) { router.pop() }
        .onChange(of: router.settledPushCount) { _, _ in
            if router.current.stack.last == entry { titleFocused = true }
        }
    }
}

// MARK: 탭 바 (단순화: 알약 이동만)

private struct SpikeTabBar: View {
    let router: SpikeRouter
    let colors: WLColors

    var body: some View {
        HStack(spacing: 0) {
            ForEach(SpikeTab.allCases) { tab in
                let on = tab == router.selected
                Button { router.select(tab) } label: {
                    Text(tab.rawValue)
                        .wlText(on ? .bodyStrong : .body)
                        .foregroundStyle(on ? colors.onTabPill : colors.onTabBar)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background {
                            if on {
                                Capsule().fill(colors.tabPill)
                                    .matchedGeometryEffect(id: "pill", in: pill)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .animation(WishlistTokens.Curve.standard.animation(ms: WishlistTokens.Motion.tabPill), value: router.selected)
        .padding(WishlistTokens.Space.s8)
        .background(colors.tabBar, in: Capsule())
        .padding(.horizontal, WishlistTokens.Space.s16)
        .padding(.bottom, WishlistTokens.Space.s8)
    }

    @Namespace private var pill
}
#endif
