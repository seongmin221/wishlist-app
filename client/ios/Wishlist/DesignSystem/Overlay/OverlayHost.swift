import Observation
import SwiftUI

/// 확인창 내용. 확인(`onConfirm`)은 창이 닫히기 시작한 뒤에 한 번만 불린다.
struct WLDialogSpec {
    let title: String
    let bullets: [String]
    let cancelText: String
    let confirmText: String
    let confirmKind: WLButtonKind
    let onConfirm: () -> Void
}

/// 메뉴 항목. 누르면 메뉴가 닫히기 시작하고 `onClick`이 불린다.
struct WLMenuItem {
    let text: String
    var icon: AnyView?
    let onClick: () -> Void

    init(text: String, icon: AnyView? = nil, onClick: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.onClick = onClick
    }
}

enum OverlayPhase { case opening, open, closing }

enum OverlayKind {
    case sheet(draggable: Bool, content: () -> AnyView)
    case dialog(WLDialogSpec)
    case menu(anchor: CGRect, items: [WLMenuItem])

    /// 뒤 화면을 흐리고 어둡게 하는 overlay(시트·확인창)인지. 메뉴는 아니다.
    var needsScrim: Bool {
        if case .menu = self { return false }
        return true
    }
}

@Observable
final class OverlayEntry: Identifiable {
    let id: Int
    let kind: OverlayKind
    var phase: OverlayPhase = .opening

    init(id: Int, kind: OverlayKind) {
        self.id = id
        self.kind = kind
    }
}

/// overlay(시트·확인창·메뉴)의 상태 기계. 화면 루트의 `OverlayHost` 하나가 이 상태를 그린다.
///
/// 각 overlay는 `opening -> open -> closing -> (제거)`를 지난다. 화면 쪽 애니메이션이 `opening`·`closing`을 끝낼 때
/// `onOpened`·`onClosed`를 부른다(Android `OverlayHostState`와 같은 규칙). 전환 중(`isAnimating`)의 규칙은 항상 같다.
///
/// - `dismiss`/`requestDismiss`: 모든 overlay가 `open`이고 대상이 가장 위일 때만 닫기를 시작한다. 열리는 중·닫히는 중이면 무시(false).
///   뒤로 연타·막 연타가 닫기 모션을 되돌리거나 겹치지 못한다.
/// - `show*`: 열리는 중이면 무시(false). 닫히는 중이면 **하나만 줄 세운다**: 닫기가 끝난 직후 열린다(나중 요청이 앞 요청을 대체).
///   "메뉴 항목 -> 닫기 -> 확인창 열기"를 호출 순서대로 써도 확인창이 사라지지 않는다. 그 외에는 바로 쌓는다.
/// - 닫기는 항상 가장 위 overlay 하나만 닫는다(확인창이 시트 위에 있으면 확인창만).
@Observable
final class OverlayHostState {
    private(set) var entries: [OverlayEntry] = []
    @ObservationIgnored private var nextId = 0
    @ObservationIgnored private var pending: (() -> Void)?

    var isAnimating: Bool { entries.contains { $0.phase != .open } }

    @discardableResult
    func showSheet<V: View>(draggable: Bool = true, @ViewBuilder _ content: @escaping () -> V) -> Bool {
        push { .sheet(draggable: draggable, content: { AnyView(content()) }) }
    }

    @discardableResult
    func showDialog(_ spec: WLDialogSpec) -> Bool { push { .dialog(spec) } }

    /// `anchor`는 눌린 버튼의 화면(전역) 기준 사각형(`View.wlAnchor`).
    @discardableResult
    func showMenu(anchor: CGRect, items: [WLMenuItem]) -> Bool { push { .menu(anchor: anchor, items: items) } }

    /// 가장 위 overlay를 닫는다. 시스템 뒤로(escape)·막 누르기가 쓴다. 닫기를 시작했으면 true.
    @discardableResult
    func dismiss() -> Bool {
        guard let top = entries.last else { return false }
        return requestDismiss(top.id)
    }

    @discardableResult
    func requestDismiss(_ id: Int) -> Bool {
        if isAnimating { return false }
        guard let top = entries.last, top.id == id else { return false }
        top.phase = .closing
        return true
    }

    /// 확인창의 확인 버튼. 닫기를 시작할 수 있을 때만(=한 번만) `onConfirm`을 부른다.
    @discardableResult
    func confirm(_ id: Int) -> Bool {
        guard let entry = entries.first(where: { $0.id == id }), case .dialog(let spec) = entry.kind else { return false }
        guard requestDismiss(id) else { return false }
        spec.onConfirm()
        return true
    }

    func onOpened(_ id: Int) {
        if let e = entries.first(where: { $0.id == id }), e.phase == .opening { e.phase = .open }
    }

    func onClosed(_ id: Int) {
        entries.removeAll { $0.id == id }
        if !entries.contains(where: { $0.phase == .closing }) {
            let next = pending
            pending = nil
            next?()
        }
    }

    private func push(_ make: @escaping () -> OverlayKind) -> Bool {
        if entries.contains(where: { $0.phase == .opening }) { return false }
        let add = { [unowned self] in
            entries.append(OverlayEntry(id: nextId, kind: make()))
            nextId += 1
        }
        if entries.contains(where: { $0.phase == .closing }) {
            pending = add
        } else {
            add()
        }
        return true
    }
}

private struct OverlayHostStateKey: EnvironmentKey {
    static let defaultValue: OverlayHostState? = nil
}

extension EnvironmentValues {
    /// 시트·메뉴·확인창 안쪽에서 `dismiss()`를 부르기 위한 접근.
    var overlayHostState: OverlayHostState? {
        get { self[OverlayHostStateKey.self] }
        set { self[OverlayHostStateKey.self] = newValue }
    }
}

extension View {
    /// 가려진 층을 접근성에서 뺀다. `.accessibilityHidden(false)`를 조상에 걸면 후손의 `.accessibilityHidden(true)`까지 취소되므로
    /// (Task 5 spike) `false`를 절대 넘기지 않고, 같은 modifier의 값만 바꾼다: 가려지면 `.ignore`(라벨 없는 한 덩어리 = 읽을 것 없음),
    /// 아니면 `.contain`(후손의 숨김·라벨을 그대로 둔다). 뷰 정체성(상태)은 바뀌지 않는다.
    func wlAccessibilityCovered(_ covered: Bool) -> some View {
        accessibilityElement(children: covered ? .ignore : .contain)
    }
}

func overlaySleep(ms: Int) async {
    try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
}

private struct AnchorKey: PreferenceKey {
    static let defaultValue = CGRect.zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = nextValue() }
}

extension View {
    /// 메뉴를 띄우는 버튼에 붙여 `showMenu`의 anchor(전역 좌표)를 얻는다.
    func wlAnchor(_ onFrame: @escaping (CGRect) -> Void) -> some View {
        background(GeometryReader { Color.clear.preference(key: AnchorKey.self, value: $0.frame(in: .global)) })
            .onPreferenceChange(AnchorKey.self, perform: onFrame)
    }
}

/// 화면 루트에 한 번 둔다(`WLTheme` 안쪽). 시스템 sheet·alert·popover·Menu를 쓰지 않고 같은 ZStack 안에서 직접 그린다.
/// 시트·확인창이 떠 있는 동안 뒤 콘텐츠에만 블러 12와 어두운 막이 걸리고, 뒤 콘텐츠는 접근성에서 숨긴다.
struct OverlayHost<Content: View>: View {
    let state: OverlayHostState
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var colors
    @State private var scrimProgress = 0.0
    @State private var scrimMounted = false

    private var scrimWanted: Bool { state.entries.contains { $0.kind.needsScrim && $0.phase != .closing } }

    var body: some View {
        let entries = state.entries
        ZStack {
            content()
                .blur(radius: CGFloat(WishlistTokens.Motion.scrimBlur) * scrimProgress)
                .wlAccessibilityCovered(!entries.isEmpty)
            if scrimMounted {
                WLScrim(progress: scrimProgress, color: colors.scrimDim) {
                    // 막 누르기는 시트만 닫는다. 확인창은 취소·확인 버튼으로만 닫힌다. 전환 중에는 dismiss가 무시한다.
                    if case .sheet = state.entries.last?.kind { state.dismiss() }
                }
                .accessibilityHidden(true)
            }
            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                layer(entry, isTop: index == entries.count - 1, hasBelow: index > 0)
            }
            // 전환 중에는 overlay 안쪽(시트 내용·메뉴 항목·확인창 버튼)도 입력을 받지 않는다(motion.md 구현 기본값).
            if state.isAnimating { InputBlocker() }
        }
        .environment(\.overlayHostState, state)
        .task(id: scrimWanted) { await runScrim(wanted: scrimWanted) }
    }

    @ViewBuilder
    private func layer(_ entry: OverlayEntry, isTop: Bool, hasBelow: Bool) -> some View {
        Group {
            switch entry.kind {
            case .sheet(let draggable, let content):
                SheetLayer(entry: entry, state: state, draggable: draggable, content: content)
            case .dialog(let spec):
                DialogLayer(entry: entry, state: state, spec: spec, dimBelow: hasBelow)
            case .menu(let anchor, let items):
                MenuLayer(entry: entry, state: state, anchor: anchor, items: items)
            }
        }
        // 가장 위가 아닌 overlay(확인창 아래의 시트)도 가려진 층이다. 가장 위는 .contain(안쪽 숨김 보존).
        .wlAccessibilityCovered(!isTop)
        .accessibilityAction(.escape) { state.dismiss() }
    }

    /// 막: 열 때 400 ease-out, 닫을 때 260 ease-in. 닫기 모션이 끝날 때까지 막(과 입력 차단)을 남긴다.
    private func runScrim(wanted: Bool) async {
        if wanted {
            scrimMounted = true
            withAnimation(WishlistTokens.Curve.easeOut.animation(ms: WishlistTokens.Motion.scrimIn)) { scrimProgress = 1 }
        } else {
            guard scrimMounted else { return }
            withAnimation(WishlistTokens.Curve.easeIn.animation(ms: WishlistTokens.Motion.scrimOut)) { scrimProgress = 0 }
            await overlaySleep(ms: WishlistTokens.Motion.scrimOut)
            if !Task.isCancelled { scrimMounted = false }
        }
    }
}
