import Observation
import SwiftUI
import UIKit

/// 확인창 내용. 확인(`onConfirm`)은 창이 닫히기 시작한 뒤에 한 번만 불린다. `target`은 제목 아래 대상 줄(썸네일 + 이름)이다.
struct WLDialogSpec {
    let title: String
    let bullets: [String]
    let cancelText: String
    let confirmText: String
    let confirmKind: WLButtonKind
    var target: WLDialogTarget?
    let onConfirm: () -> Void

    init(title: String, bullets: [String], cancelText: String, confirmText: String, confirmKind: WLButtonKind,
         target: WLDialogTarget? = nil, onConfirm: @escaping () -> Void) {
        self.title = title
        self.bullets = bullets
        self.cancelText = cancelText
        self.confirmText = confirmText
        self.confirmKind = confirmKind
        self.target = target
        self.onConfirm = onConfirm
    }
}

/// 확인창 대상 줄(보드 삭제 확인): 묶음 면 위 썸네일 44 + 15/700 이름.
struct WLDialogTarget {
    let text: String
    let thumbnail: AnyView
}

/// 메뉴 항목. 누르면 메뉴가 닫히기 시작하고 `onClick`이 불린다. `enabled`가 false면 흐리게(0.4) 그리고 누를 수 없다.
struct WLMenuItem {
    let text: String
    var icon: AnyView?
    var enabled: Bool
    let onClick: () -> Void

    init(text: String, icon: AnyView? = nil, enabled: Bool = true, onClick: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.enabled = enabled
        self.onClick = onClick
    }
}

enum OverlayPhase { case opening, open, closing }

enum OverlayKind {
    case sheet(title: String, draggable: Bool, content: () -> AnyView)
    case dialog(WLDialogSpec)
    case menu(anchor: CGRect, items: [WLMenuItem])

    var accessibilityTitle: String {
        switch self {
        case .sheet(let title, _, _): title
        case .dialog(let spec): spec.title
        case .menu: String(localized: "wl.menu")
        }
    }

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
/// - `dismissAll`: 쌓인 overlay를 위에서부터 차례로 모두 닫는다. 가장 위가 이미 닫히는 중이어도 받는다(줄 세운 닫기).
///   확인창의 `onConfirm`(창이 닫히기 시작한 뒤 불린다) 안에서 불러 "확인 -> 아래 시트까지 닫기"를 쓴다.
///   그 전에 줄 세운 `show*`는 버린다(그 뒤의 `show*`는 모두 닫힌 뒤 열린다).
@Observable
final class OverlayHostState {
    private(set) var entries: [OverlayEntry] = []
    @ObservationIgnored private var nextId = 0
    @ObservationIgnored private var pending: (() -> Void)?
    @ObservationIgnored private var dismissingAll = false

    var isAnimating: Bool { entries.contains { $0.phase != .open } }

    @discardableResult
    func showSheet<V: View>(title: String = String(localized: "wl.sheet"), draggable: Bool = true, @ViewBuilder _ content: @escaping () -> V) -> Bool {
        push { .sheet(title: title, draggable: draggable, content: { AnyView(content()) }) }
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

    /// 모든 overlay를 위에서부터 차례로 닫는다. 열리는 중인 overlay가 있으면 무시(false).
    /// 가장 위가 `open`이면 바로 닫기 시작하고, 이미 `closing`이면 그 닫기가 끝난 뒤 이어서 닫는다.
    @discardableResult
    func dismissAll() -> Bool {
        guard let top = entries.last, !entries.contains(where: { $0.phase == .opening }) else { return false }
        dismissingAll = true
        pending = nil
        if top.phase == .open { top.phase = .closing }
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
            if dismissingAll {
                if let top = entries.last {
                    top.phase = .closing
                    return
                }
                dismissingAll = false
            }
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
    /// 가려진 층을 접근성에서 뺀다. 같은 modifier의 값만 바꿔 뷰 정체성(상태)은 바뀌지 않는다.
    /// - 가려짐: `.ignore` + `accessibilityHidden(true)`. 층 전체가 트리에서 빠진다(`.ignore`만 쓰면 라벨 없는 빈 요소가 남았다).
    /// - 보임: `.contain` + `accessibilityHidden(false)`. 맨 조상에 그냥 `accessibilityHidden(false)`를 걸면 후손의
    ///   `accessibilityHidden(true)`까지 취소되지만(Task 5 spike), `.contain` 컨테이너 뒤에 걸면 후손의 숨김이 그대로 남는다
    ///   (Task 7에서 serve-sim `/ax`로 iOS 26.5·17.5 확인).
    func wlAccessibilityCovered(_ covered: Bool) -> some View {
        accessibilityElement(children: covered ? .ignore : .contain)
            .accessibilityHidden(covered)
    }
}

@MainActor
func overlayAnimate(_ animation: Animation?, changes: () -> Void) async {
    await withCheckedContinuation { continuation in
        withAnimation(animation, completionCriteria: .removed, changes) {
            continuation.resume()
        }
    }
}

/// 메뉴를 띄우는 버튼의 자리. 버튼 뒤에 둔 보이지 않는 UIKit 뷰를 약하게 들고, 누른 순간에만 window 좌표를 읽는다
/// (`frame`). 관찰하지 않는 참조라 스크롤·탭 전환 중 위치가 바뀌어도 화면을 다시 그리지 않는다.
/// PreferenceKey 방식은 탭 셸 안에서 늘 `.zero`가 왔고, 위치 변화마다 상태를 쓰는 방식은 스크롤마다 화면 전체를 다시 그려 쓰지 않는다.
/// 화면에서는 `@State private var anchor = WLAnchor()`로 들고 `.wlAnchor(anchor)`를 붙인 뒤 `anchor.frame`을 넘긴다.
final class WLAnchor {
    fileprivate weak var view: UIView?

    var frame: CGRect {
        guard let view, view.window != nil else { return .zero }
        return view.convert(view.bounds, to: nil)
    }
}

private struct WLAnchorProbe: UIViewRepresentable {
    let anchor: WLAnchor

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        anchor.view = view
        return view
    }

    func updateUIView(_ view: UIView, context: Context) { anchor.view = view }
}

extension View {
    /// 메뉴를 띄우는 버튼에 붙인다. 누를 때 `anchor.frame`(전역 좌표)을 `showMenu`에 넘긴다.
    func wlAnchor(_ anchor: WLAnchor) -> some View {
        background(WLAnchorProbe(anchor: anchor))
    }
}

/// 화면 루트에 한 번 둔다(`WLTheme` 안쪽). 시스템 sheet·alert·popover·Menu를 쓰지 않고 같은 ZStack 안에서 직접 그린다.
/// 시트·확인창이 떠 있는 동안 뒤 콘텐츠에만 블러 12와 어두운 막이 걸리고, 뒤 콘텐츠는 접근성에서 숨긴다.
struct OverlayHost<Content: View>: View {
    let state: OverlayHostState
    @ViewBuilder var content: () -> Content

    @Environment(\.wlColors) private var colors
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
            case .sheet(_, let draggable, let content):
                SheetLayer(entry: entry, state: state, draggable: draggable, content: content)
            case .dialog(let spec):
                DialogLayer(entry: entry, state: state, spec: spec, dimBelow: hasBelow)
            case .menu(let anchor, let items):
                MenuLayer(entry: entry, state: state, anchor: anchor, items: items)
            }
        }
        // 가장 위가 아닌 overlay(확인창 아래의 시트)도 가려진 층이다(트리에서 빠진다). 가장 위는 .contain(안쪽 숨김 보존).
        // escape는 층 컨테이너에 건다(VoiceOver는 초점 요소에서 컨테이너를 따라 올라가며 escape를 보낸다). 가장 위 층만 닫는다.
        .wlAccessibilityCovered(!isTop || entry.phase != .open)
        .accessibilityLabel(entry.kind.accessibilityTitle)
        .accessibilityAction(.escape) { if isTop { state.dismiss() } }
    }

    /// 막: 열 때 400 ease-out, 닫을 때 260 ease-in. 닫기 모션이 끝날 때까지 막(과 입력 차단)을 남긴다.
    private func runScrim(wanted: Bool) async {
        if wanted {
            scrimMounted = true
            withAnimation(reduceMotion ? nil : WishlistTokens.Curve.easeOut.animation(ms: WishlistTokens.Motion.scrimIn)) { scrimProgress = 1 }
        } else {
            guard scrimMounted else { return }
            await overlayAnimate(reduceMotion ? nil : WishlistTokens.Curve.easeIn.animation(ms: WishlistTokens.Motion.scrimOut)) { scrimProgress = 0 }
            if !Task.isCancelled { scrimMounted = false }
        }
    }
}
