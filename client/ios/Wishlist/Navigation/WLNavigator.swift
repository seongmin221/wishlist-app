import Observation

/// 스택의 한 칸. `id`는 앱 안에서 유일하고 늘어나기만 한다(화면 정체성·모션 상태의 키).
struct WLBackStackEntry: Identifiable, Equatable {
    let id: Int
    let route: WLRoute
    /// 이 화면을 연 요소의 공유 요소 키(탭 첫 화면은 nil). 탭 이름을 앞에 둔다(예: `home/product/p1`).
    let sourceKey: String?
}

/// 진행 중인 전환. 끝나면 `WLNavHost`가 `finishTransition()`을 부른다. `serial`이 전환마다 달라서
/// 화면 쪽은 "자기가 시작한 전환"일 때만 끝낸다(Android `activeTransition === mine`).
struct WLNavTransition: Equatable {
    enum Kind: Equatable {
        case push, pop
        case tab(from: WLTab)
        /// `replaceTop`: 맨 위 칸이 다른 칸으로 바뀐다(cross-fade). 바뀐 칸은 `exiting`으로 남는다.
        case replace
        /// 손가락으로 끄는 중(가장자리 끌어 뒤로). 놓으면 `pop`이 되거나 취소된다.
        case backGesture
    }

    let kind: Kind
    let tab: WLTab
    let serial: Int
}

/// pop·replace된 뒤 모션이 끝날 때까지 화면에 남는 칸.
struct WLExitingEntry: Equatable {
    let tab: WLTab
    let entry: WLBackStackEntry
}

/// 탭별로 독립된 화면 스택과 현재 탭을 가진다. SwiftUI에 묶이지 않은 상태 기계라 단위 테스트로 검증한다(Android `WLNavigator`와 같은 규칙).
///
/// 전환 규칙(motion.md 구현 기본값 "전환 중 입력"):
/// - `push`·`pop`·`selectTab`(다른 탭)은 전환을 시작하고(`isTransitioning = true`) 바로 상태를 바꾼다. 화면 쪽이 모션을 끝내면
///   `finishTransition()`을 부른다.
/// - 전환 중에는 `push`·`pop`·`selectTab`·`beginBackGesture`를 모두 무시한다(false). 전환 중 탭을 누르거나 뒤로 가도
///   전환 층(날아가는 사진)이 남지 않는다.
/// - 현재 탭을 다시 고르면 전환 없이 `scrollToTopRequest`에 그 탭을 둔다. 탭 첫 화면이 맨 위로 스크롤하고 `consumeScrollToTop`을 부른다.
/// - 끌어서 뒤로: `beginBackGesture` → (`commitBackGesture` → 모션 끝에 `finishTransition`) 또는 `cancelBackGesture`.
/// - pop된 칸은 `exiting`으로 남아 뒤로 모션 동안 계속 그려지고 `finishTransition()`에서 지워진다.
/// - 스택에서 빠진 칸의 id는 `drainRemoved()`로 한 번씩 내보낸다. 셸이 전환이 끝난 뒤 그 칸의 owner(`WLEntryOwners`)를 닫는다.
@Observable
final class WLNavigator {
    private(set) var currentTab: WLTab
    private var stacks: [WLTab: [WLBackStackEntry]] = [:]
    private(set) var activeTransition: WLNavTransition?
    private(set) var exiting: WLExitingEntry?
    /// 현재 탭을 다시 눌렀을 때 그 탭. 탭 첫 화면이 맨 위로 부드럽게 스크롤한다.
    private(set) var scrollToTopRequest: WLTab?

    /// 아직 내보내지 않은 빠진 칸 id(빠진 순서). 관찰되므로 셸이 "전환 없음 + 빠진 칸 있음"을 지켜볼 수 있다.
    private var removed: [Int] = []

    @ObservationIgnored private var nextId = 0
    @ObservationIgnored private var nextSerial = 0

    init(initialTab: WLTab = .home) {
        currentTab = initialTab
        for tab in WLTab.allCases { stacks[tab] = [makeEntry(.tabRoot(tab), sourceKey: nil)] }
    }

    var isTransitioning: Bool { activeTransition != nil }

    var canPop: Bool { entries(currentTab).count > 1 }

    var hasRemoved: Bool { !removed.isEmpty }

    func stack(_ tab: WLTab) -> [WLRoute] { entries(tab).map(\.route) }

    func entries(_ tab: WLTab) -> [WLBackStackEntry] { stacks[tab] ?? [] }

    @discardableResult
    func selectTab(_ tab: WLTab) -> Bool {
        if isTransitioning { return false }
        if tab == currentTab {
            scrollToTopRequest = tab
            return true
        }
        begin(.tab(from: currentTab), tab: tab)
        currentTab = tab
        return true
    }

    func consumeScrollToTop(_ tab: WLTab) {
        if scrollToTopRequest == tab { scrollToTopRequest = nil }
    }

    /// `sourceKey`는 누른 요소의 공유 요소 키. 다음 화면이 같은 키로 이어 받는다.
    @discardableResult
    func push(_ route: WLRoute, sourceKey: String) -> Bool {
        if isTransitioning { return false }
        let tab = currentTab
        begin(.push, tab: tab)
        stacks[tab, default: []].append(makeEntry(route, sourceKey: sourceKey))
        return true
    }

    /// 맨 위 화면을 닫는다. 탭 첫 화면이거나 전환 중이면 false.
    @discardableResult
    func pop() -> Bool {
        if isTransitioning || !canPop { return false }
        begin(.pop, tab: currentTab)
        removeTop(currentTab)
        return true
    }

    /// Replaces the current tab's top entry (cross-fade); the replaced entry's id is reported as removed.
    /// 새 칸은 새 id를 받고 이전 칸의 `sourceKey`를 이어 받는다. 탭 첫 화면이거나 전환 중이면 false.
    @discardableResult
    func replaceTop(_ route: WLRoute) -> Bool {
        if isTransitioning || !canPop { return false }
        let tab = currentTab
        guard let old = stacks[tab]?.popLast() else { return false }
        begin(.replace, tab: tab)
        stacks[tab, default: []].append(makeEntry(route, sourceKey: old.sourceKey))
        exiting = WLExitingEntry(tab: tab, entry: old)
        removed.append(old.id)
        return true
    }

    /// Pops, in every tab, the first account-scoped route and everything above it; no transition. Returns removed entry ids.
    /// 전환 중에도 바로 적용한다(이전 계정 화면을 남기지 않는다). 영향받은 탭의 끌어서 뒤로·push·replace는 그 자리에서 끝낸다
    /// (끌던 칸·들어오던 칸이 사라졌으므로 뒤이은 commit·cancel은 아무 일도 하지 않고, 모션의 끝 콜백도 자기 전환이 아니라 무시된다).
    /// pop 중이면 떠나는 칸(`exiting`)의 모션은 그대로 끝까지 간다.
    func dropAccountScoped() -> [Int] {
        var dropped: [Int] = []
        for tab in WLTab.allCases {
            guard let stack = stacks[tab], let first = stack.firstIndex(where: { $0.route.accountScoped }), first >= 1 else { continue }
            dropped += stack[first...].map(\.id)
            stacks[tab] = Array(stack[..<first])
            if let t = activeTransition, t.tab == tab {
                switch t.kind {
                case .backGesture, .push:
                    activeTransition = nil
                case .replace:
                    activeTransition = nil
                    exiting = nil
                case .pop, .tab:
                    break
                }
            }
        }
        removed += dropped
        return dropped
    }

    /// Ids of entries removed by pop/commitBackGesture/replaceTop/dropAccountScoped since the last call.
    func drainRemoved() -> [Int] {
        if removed.isEmpty { return [] }
        defer { removed = [] }
        return removed
    }

    func finishTransition() {
        activeTransition = nil
        exiting = nil
    }

    func beginBackGesture() -> Bool {
        if isTransitioning || !canPop { return false }
        begin(.backGesture, tab: currentTab)
        return true
    }

    /// 끌어서 뒤로를 확정한다. 스택에서 빼고, 남은 뒤로 모션이 끝나면 `finishTransition()`.
    func commitBackGesture() {
        guard let gesture = activeTransition, gesture.kind == .backGesture else { return }
        begin(.pop, tab: gesture.tab)
        if entries(gesture.tab).count > 1 { removeTop(gesture.tab) }
    }

    /// 끌어서 뒤로를 취소한다(되돌림 모션이 끝난 뒤 부른다).
    func cancelBackGesture() {
        if activeTransition?.kind == .backGesture { activeTransition = nil }
    }

    private func begin(_ kind: WLNavTransition.Kind, tab: WLTab) {
        activeTransition = WLNavTransition(kind: kind, tab: tab, serial: nextSerial)
        nextSerial += 1
    }

    private func removeTop(_ tab: WLTab) {
        guard let top = stacks[tab]?.popLast() else { return }
        exiting = WLExitingEntry(tab: tab, entry: top)
        removed.append(top.id)
    }

    private func makeEntry(_ route: WLRoute, sourceKey: String?) -> WLBackStackEntry {
        defer { nextId += 1 }
        return WLBackStackEntry(id: nextId, route: route, sourceKey: sourceKey)
    }
}
