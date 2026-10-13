import Observation
import SwiftUI

/// 스택 칸 하나의 모션 값. 값마다 관찰 단위가 따로라(@Observable은 속성별로 추적한다) 사진 위치(`phase`)와 내용 불투명도
/// (`content`)를 서로 다른 곡선의 `withAnimation`으로 움직여도 각자의 곡선을 탄다. 새 칸의 기본값은 "전환 시작 전" 상태다.
@Observable
final class WLEntryChannels {
    /// 사진: 0(원래 자리) → 1(상세 자리). 밀기: 0(화면 오른쪽 밖) → 1(제자리).
    var phase: Double = 0
    /// 사진 이동은 상세 내용 불투명도. 밀기는 내용을 옅게 하지 않고 탭 바가 보이고 안 보이는 화면 사이에서 탭 바를 옅게 한다.
    var content: Double = 0
    /// 공유 요소를 전환 층이 그리는 중(상세 안의 사진은 숨는다).
    var animating = true
    /// 칸 전체 불투명도. `replaceTop`의 cross-fade만 움직인다(그 밖에는 늘 1).
    var fade: Double = 1
    /// window 좌표. 원래 자리는 push 때, pop·끌기 시작 때 다시 읽는다. 상세 자리도 같다.
    var source: CGRect = .zero
    var target: CGRect = .zero
}

/// push가 끝난 칸(같은 칸이 다시 도착해도 바뀌도록 serial을 둔다).
struct WLArrival: Equatable {
    let entryID: Int
    let serial: Int
}

@Observable
final class WLTabChannels {
    var opacity: Double
    var scale: Double = 1

    init(opacity: Double) { self.opacity = opacity }
}

/// `WLNavigator`의 전환을 화면 모션으로 옮긴다. 시간·곡선은 모두 `WishlistTokens.Motion`·`Curve` 생성 상수다.
/// 전환이 끝나면 그 전환이 아직 진행 중일 때만 `finishTransition()`을 부른다.
@Observable
final class WLNavMotion {
    private typealias M = WishlistTokens.Motion
    private typealias C = WishlistTokens.Curve

    /// 끌어서 뒤로를 "빠르게 놓음"으로 보는 진행 속도(초당). Android와 같은 구현 기본값.
    static let flingProgressPerSecond = 1.5
    /// 남은 거리 비율로 줄인 모션의 최소 시간. 거의 끌지 않았을 때 튀어 보이지 않게 하는 구현 기본값(Android와 같다).
    static let revertMinMillis = 120

    @ObservationIgnored var reduceMotion = false
    /// push가 끝나 포커스를 받을 칸. VoiceOver 포커스를 상세 제목으로 옮기는 데 쓴다.
    private(set) var arrival: WLArrival?

    @ObservationIgnored let registry = WLSharedRegistry()
    @ObservationIgnored let navigator: WLNavigator
    @ObservationIgnored private var entryChannels: [Int: WLEntryChannels] = [:]
    @ObservationIgnored private var tabChannels: [WLTab: WLTabChannels] = [:]
    @ObservationIgnored private var drag: (entry: WLBackStackEntry, transition: WLNavTransition)?
    @ObservationIgnored private var pendingPopRemaining: Double?
    @ObservationIgnored private var arrivalSerial = 0
    /// Entries whose own content takes the left-edge swipe (FWebView with page history: WKWebView goes back).
    @ObservationIgnored private var edgeBackBlocked: Set<Int> = []

    init(navigator: WLNavigator) {
        self.navigator = navigator
        for tab in WLTab.allCases { tabChannels[tab] = WLTabChannels(opacity: tab == navigator.currentTab ? 1 : 0) }
    }

    /// 칸의 모션 값(없으면 시작 상태로 만든다). 관찰하지 않는 저장소라 만들어도 화면을 다시 그리지 않는다.
    func channels(_ id: Int) -> WLEntryChannels {
        if let ch = entryChannels[id] { return ch }
        let ch = WLEntryChannels()
        entryChannels[id] = ch
        return ch
    }

    func tab(_ tab: WLTab) -> WLTabChannels { tabChannels[tab]! }

    // MARK: 전환 시작

    func handle(_ transition: WLNavTransition?) {
        guard let t = transition else { return }
        if reduceMotion, t.kind != .backGesture {
            snap {
                switch t.kind {
                case .push:
                    if let entry = navigator.entries(t.tab).last {
                        let ch = channels(entry.id)
                        ch.phase = 1
                        ch.content = 1
                        settlePush(entry, t)
                    }
                case .pop:
                    pendingPopRemaining = nil
                    if let entry = navigator.exiting?.entry {
                        if let key = entry.sourceKey { registry.setHidden(key, false) }
                        entryChannels[entry.id] = nil
                    }
                    finish(t)
                case .tab(let from):
                    tab(from).opacity = 0
                    tab(t.tab).opacity = 1
                    tab(t.tab).scale = 1
                    finish(t)
                case .replace:
                    if let entry = navigator.entries(t.tab).last { settleIncoming(channels(entry.id)) }
                    if let old = navigator.exiting?.entry { entryChannels[old.id] = nil }
                    finish(t)
                case .backGesture: break
                }
            }
            return
        }
        switch t.kind {
        case .push:
            guard let entry = navigator.entries(t.tab).last else { return finish(t) }
            startPush(entry, t)
        case .pop:
            guard let exiting = navigator.exiting else { return finish(t) }
            let remaining = pendingPopRemaining ?? 1
            pendingPopRemaining = nil
            startPop(exiting.entry, t, remaining: remaining)
        case .tab(let from):
            startTab(from: from, to: t.tab, t)
        case .replace:
            guard let entry = navigator.entries(t.tab).last, let old = navigator.exiting?.entry else { return finish(t) }
            startReplace(entry, replacing: old, t)
        case .backGesture:
            break // 끌기는 beginDrag가 직접 움직인다.
        }
    }

    private func startPush(_ entry: WLBackStackEntry, _ t: WLNavTransition) {
        let ch = channels(entry.id)
        snap {
            ch.phase = 0
            ch.content = 0
            ch.animating = true
            ch.source = entry.route.pushStyle == .photo ? registry.frame(entry.sourceKey) ?? .zero : .zero
        }
        switch entry.route.pushStyle {
        case .photo:
            if let key = entry.sourceKey { registry.setHidden(key, true) }
            Task { @MainActor in
                // 상세 자리가 재어지기 전(.zero)에는 움직이지 않는다. 그동안 사진은 원래 자리에, 상세는 투명하게 있다.
                ch.target = await measuredTarget(entry.id) ?? ch.source
                withAnimation(C.emphasized.animation(ms: M.pushPhotoOpen)) { ch.phase = 1 } completion: { [weak self] in
                    self?.settlePush(entry, t)
                }
                withAnimation(C.easeOut.animation(ms: M.pushPhotoContent)) { ch.content = 1 }
            }
        case .slide:
            // 새 화면은 오른쪽 밖(W) → 0, 아래 화면은 0 → −parallax·W(`WLNavHost`가 phase로 그린다). 페이드·막·그림자 없음.
            withAnimation(C.emphasized.animation(ms: M.pushSlideOpen)) {
                ch.phase = 1
                ch.content = 1
            } completion: { [weak self] in
                self?.settlePush(entry, t)
            }
        }
    }

    private func settlePush(_ entry: WLBackStackEntry, _ t: WLNavTransition) {
        // The push was ended by `dropAccountScoped` (its entry is gone): only give the source photo back.
        guard navigator.activeTransition == t else {
            if let key = entry.sourceKey { registry.setHidden(key, false) }
            entryChannels[entry.id] = nil
            return
        }
        let ch = channels(entry.id)
        ch.animating = false
        if let key = entry.sourceKey { registry.setHidden(key, false) }
        arrivalSerial += 1
        arrival = WLArrival(entryID: entry.id, serial: arrivalSerial)
        finish(t)
    }

    /// `remaining`: 남은 거리 비율(끌어서 뒤로 확정이면 1보다 작다).
    private func startPop(_ entry: WLBackStackEntry, _ t: WLNavTransition, remaining: Double) {
        let ch = channels(entry.id)
        let style = entry.route.pushStyle
        if !ch.animating { prepareBack(entry, ch) }
        let done: () -> Void = { [weak self] in
            guard let self else { return }
            if let key = entry.sourceKey { registry.setHidden(key, false) }
            finish(t)
            entryChannels[entry.id] = nil
        }
        switch style {
        case .photo:
            withAnimation(C.emphasized.animation(ms: scaled(M.pushPhotoBack, remaining))) { ch.phase = 0 } completion: { done() }
            withAnimation(C.easeIn.animation(ms: scaled(M.pushPhotoBackContent, remaining))) { ch.content = 0 }
        case .slide:
            withAnimation(C.emphasized.animation(ms: scaled(M.pushSlideBack, remaining))) {
                ch.phase = 0
                ch.content = 0
            } completion: { done() }
        }
    }

    /// 뒤로(버튼·끌기) 시작: 공유 요소를 다시 전환 층으로 넘기고 두 자리를 지금 위치로 다시 잰다
    /// (상세를 스크롤했으면 사진은 지금 보이는 자리에서 돌아간다). 못 재면 push 때 잡은 값을 쓴다.
    private func prepareBack(_ entry: WLBackStackEntry, _ ch: WLEntryChannels) {
        snap {
            if let source = registry.frame(entry.sourceKey) { ch.source = source }
            if entry.route.pushStyle == .photo, let target = registry.frame(WLSharedRegistry.targetKey(entry.id)) { ch.target = target }
            ch.animating = true
        }
        if entry.route.pushStyle == .photo, let key = entry.sourceKey { registry.setHidden(key, true) }
    }

    private func startTab(from: WLTab, to: WLTab, _ t: WLNavTransition) {
        let out = tab(from)
        let incoming = tab(to)
        snap {
            incoming.opacity = 0
            incoming.scale = M.tabIncomingScale
        }
        withAnimation(C.easeIn.animation(ms: M.tabOutgoing)) { out.opacity = 0 }
        withAnimation(C.fadeIn.animation(ms: M.tabIncoming).delay(Double(M.tabIncomingDelay) / 1000)) {
            incoming.opacity = 1
            incoming.scale = 1
        } completion: { [weak self] in
            self?.finish(t)
        }
    }

    /// `replaceTop`: 새 칸은 제자리에서 0 → 1, 바뀐 칸(`exiting`, 위에 그려진다)은 1 → 0으로 함께 옅어진다
    /// (200 `easeOut`, Android `ReplaceCrossFadeMillis`와 같은 구현 기본값). 사진·밀기 모션은 없다.
    private func startReplace(_ entry: WLBackStackEntry, replacing old: WLBackStackEntry, _ t: WLNavTransition) {
        let incoming = channels(entry.id)
        let outgoing = channels(old.id)
        snap {
            settleIncoming(incoming)
            incoming.fade = 0
        }
        withAnimation(C.easeOut.animation(ms: M.dialogIn)) {
            incoming.fade = 1
            outgoing.fade = 0
        } completion: { [weak self] in
            guard let self else { return }
            finish(t)
            entryChannels[old.id] = nil
        }
    }

    /// 들어온 칸을 제자리·불투명·전환 층 없음으로 둔다(replace는 사진을 날리지 않는다).
    private func settleIncoming(_ ch: WLEntryChannels) {
        ch.phase = 1
        ch.content = 1
        ch.animating = false
        ch.fade = 1
    }

    // MARK: 계정 범위 화면 정리

    /// `navigator.dropAccountScoped()`를 부르고 빠진 칸의 모션 상태를 정리한다: 끌던 칸이면 끌기를 잊고(이후 끌기 이벤트는
    /// 아무 일도 하지 않는다), 숨겨 둔 원래 자리 사진을 되돌리고, 칸의 모션 값을 지운다. 화면은 스택만 그리므로 남은 모션은
    /// 지금 맨 위 칸에 머문다. 빠진 칸 id를 그대로 돌려준다(셸이 그 owner를 바로 닫는다).
    func dropAccountScoped() -> [Int] {
        var keys: [Int: String] = [:]
        for tab in WLTab.allCases {
            for entry in navigator.entries(tab) { if let key = entry.sourceKey { keys[entry.id] = key } }
        }
        let exitingBefore = navigator.exiting?.entry
        let dropped = navigator.dropAccountScoped()
        if let old = exitingBefore, navigator.exiting == nil { entryChannels[old.id] = nil } // a replace that ended
        if let drag, navigator.activeTransition != drag.transition {
            self.drag = nil
            pendingPopRemaining = nil
        }
        for id in dropped {
            if let key = keys[id] { registry.setHidden(key, false) }
            entryChannels[id] = nil
        }
        return dropped
    }

    // MARK: 끌어서 뒤로 (진행값 0~1)

    /// 가장자리 끌기를 받아도 되는지(전환 중·탭 첫 화면이면 아니다, 맨 위 칸이 끌기를 직접 쓰면 아니다).
    var canBeginDrag: Bool {
        guard !navigator.isTransitioning, navigator.canPop else { return false }
        guard let top = navigator.entries(navigator.currentTab).last else { return false }
        return !edgeBackBlocked.contains(top.id)
    }

    /// 칸이 왼쪽 가장자리 끌기를 직접 쓰는지(웹뷰: 기록이 있으면 `allowsBackForwardNavigationGestures`가 뒤로 간다).
    func setEdgeBackBlocked(_ entryID: Int, _ blocked: Bool) {
        if blocked { edgeBackBlocked.insert(entryID) } else { edgeBackBlocked.remove(entryID) }
    }

    func beginDrag() -> Bool {
        guard let entry = navigator.entries(navigator.currentTab).last, navigator.beginBackGesture(),
              let t = navigator.activeTransition else { return false }
        drag = (entry, t)
        if !reduceMotion { prepareBack(entry, channels(entry.id)) }
        return true
    }

    func updateDrag(_ progress: Double) {
        guard let drag, navigator.activeTransition == drag.transition else { return }
        let p = min(1, max(0, progress))
        let ch = channels(drag.entry.id)
        snap {
            // 사진: 상세 자리(1) → 원래 자리 쪽. 밀기: 제자리(1) → 오른쪽으로(위 화면 = p·W, 아래 화면 = −parallax·W·(1−p)).
            if !reduceMotion { ch.phase = 1 - p }
            ch.content = 1 - p
        }
    }

    /// 놓음: 50% 이상이거나 빠르게 놓았으면(초당 1.5 이상) 확정, 아니면 되돌린다. 50%를 넘긴 뒤 반대로 빠르게 튕겨도
    /// 진행 50% 이상이면 확정한다(Android와 같은 규칙).
    func endDrag(progress: Double, velocity: Double) {
        guard let drag else { return }
        self.drag = nil
        // The gesture was ended elsewhere (dropAccountScoped): nothing to commit or revert.
        guard navigator.activeTransition == drag.transition else { return }
        let p = min(1, max(0, progress))
        let commit = p >= M.interactiveBackCommitProgress || velocity >= Self.flingProgressPerSecond
        if commit {
            pendingPopRemaining = 1 - p
            navigator.commitBackGesture() // activeTransition이 pop으로 바뀌면 handle이 남은 뒤로 모션을 재생한다.
            return
        }
        let ch = channels(drag.entry.id)
        let style = drag.entry.route.pushStyle
        let ms = scaled(style == .photo ? M.pushPhotoBack : M.pushSlideBack, p)
        withAnimation(reduceMotion ? nil : C.emphasized.animation(ms: ms)) {
            ch.phase = 1
            ch.content = 1
        } completion: { [weak self] in
            ch.animating = false
            if let key = drag.entry.sourceKey { self?.registry.setHidden(key, false) }
            // Only our own gesture: a drop during the revert ended it, and a later gesture is not ours.
            if self?.navigator.activeTransition == drag.transition { self?.navigator.cancelBackGesture() }
        }
    }

    // MARK: 도움

    private func finish(_ t: WLNavTransition) {
        if navigator.activeTransition == t { navigator.finishTransition() }
    }

    private func measuredTarget(_ id: Int) async -> CGRect? {
        for _ in 0..<30 {
            if let rect = registry.frame(WLSharedRegistry.targetKey(id)) { return rect }
            try? await Task.sleep(nanoseconds: 16_000_000)
        }
        return nil
    }

    private func scaled(_ ms: Int, _ fraction: Double) -> Int {
        max(Self.revertMinMillis, Int(Double(ms) * min(1, max(0, fraction))))
    }

    private func snap(_ change: () -> Void) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t, change)
    }
}
