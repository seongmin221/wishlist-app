import Observation
import SwiftUI

/// 스택 칸 하나의 모션 값. 값마다 관찰 단위가 따로라(@Observable은 속성별로 추적한다) 사진 위치(`phase`)와 내용 불투명도
/// (`content`)를 서로 다른 곡선의 `withAnimation`으로 움직여도 각자의 곡선을 탄다. 새 칸의 기본값은 "전환 시작 전" 상태다.
@Observable
final class WLEntryChannels {
    /// 사진 0→1, 면 0→1→2.
    var phase: Double = 0
    var content: Double = 0
    /// 공유 요소를 전환 층이 그리는 중(상세 안의 사진은 숨는다).
    var animating = true
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

    /// 전환 동안 비워 두는 원래 사진 자리(key).
    private(set) var hiddenSources: Set<String> = []
    /// push가 끝나 포커스를 받을 칸. VoiceOver 포커스를 상세 제목으로 옮기는 데 쓴다.
    private(set) var arrival: WLArrival?

    @ObservationIgnored let registry = WLSharedRegistry()
    @ObservationIgnored let navigator: WLNavigator
    @ObservationIgnored private var entryChannels: [Int: WLEntryChannels] = [:]
    @ObservationIgnored private var tabChannels: [WLTab: WLTabChannels] = [:]
    @ObservationIgnored private var drag: (entry: WLBackStackEntry, transition: WLNavTransition)?
    @ObservationIgnored private var pendingPopRemaining: Double?
    @ObservationIgnored private var arrivalSerial = 0

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
            ch.source = registry.frame(entry.sourceKey) ?? .zero
        }
        switch entry.route.pushStyle {
        case .photo:
            if let key = entry.sourceKey { hiddenSources.insert(key) }
            Task { @MainActor in
                // 상세 자리가 재어지기 전(.zero)에는 움직이지 않는다. 그동안 사진은 원래 자리에, 상세는 투명하게 있다.
                ch.target = await measuredTarget(entry.id) ?? ch.source
                withAnimation(C.emphasized.animation(ms: M.pushPhotoOpen)) { ch.phase = 1 } completion: { [weak self] in
                    self?.settlePush(entry, t)
                }
                withAnimation(C.easeOut.animation(ms: M.pushPhotoContent)) { ch.content = 1 }
            }
        case .surface:
            withAnimation(C.easeOut.animation(ms: M.pushSurfaceLift)) { ch.phase = 1 } completion: { [weak self] in
                withAnimation(C.emphasized.animation(ms: M.pushSurfaceExpand)) { ch.phase = 2 } completion: {
                    self?.settlePush(entry, t)
                }
            }
            // 다음 화면 내용: 커짐 시작 후 190부터 230 동안.
            let delay = Double(M.pushSurfaceLift + M.pushSurfaceContentDelay) / 1000
            withAnimation(C.easeOut.animation(ms: M.pushSurfaceContent).delay(delay)) { ch.content = 1 }
        }
    }

    private func settlePush(_ entry: WLBackStackEntry, _ t: WLNavTransition) {
        let ch = channels(entry.id)
        ch.animating = false
        if let key = entry.sourceKey { hiddenSources.remove(key) }
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
            if let key = entry.sourceKey { hiddenSources.remove(key) }
            finish(t)
            entryChannels[entry.id] = nil
        }
        switch style {
        case .photo:
            withAnimation(C.emphasized.animation(ms: scaled(M.pushPhotoBack, remaining))) { ch.phase = 0 } completion: { done() }
            withAnimation(C.easeIn.animation(ms: scaled(M.pushPhotoBackContent, remaining))) { ch.content = 0 }
        case .surface:
            withAnimation(C.easeIn.animation(ms: scaled(M.pushSurfaceBackContent, remaining))) { ch.content = 0 }
            withAnimation(C.emphasized.animation(ms: scaled(M.pushSurfaceBack, remaining))) { ch.phase = 1 } completion: {
                withAnimation(C.easeIn.animation(ms: M.pushSurfaceSettle)) { ch.phase = 0 } completion: { done() }
            }
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
        if entry.route.pushStyle == .photo, let key = entry.sourceKey { hiddenSources.insert(key) }
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

    // MARK: 끌어서 뒤로 (진행값 0~1)

    /// 가장자리 끌기를 받아도 되는지(전환 중·탭 첫 화면이면 아니다).
    var canBeginDrag: Bool { !navigator.isTransitioning && navigator.canPop }

    func beginDrag() -> Bool {
        guard let entry = navigator.entries(navigator.currentTab).last, navigator.beginBackGesture(),
              let t = navigator.activeTransition else { return false }
        drag = (entry, t)
        prepareBack(entry, channels(entry.id))
        return true
    }

    func updateDrag(_ progress: Double) {
        guard let drag else { return }
        let p = min(1, max(0, progress))
        let ch = channels(drag.entry.id)
        snap {
            // 사진: 상세 자리(1) → 원래 자리 쪽. 면: 화면 전체(2) → 떠오름(1) 쪽. 내용은 같이 옅어진다.
            ch.phase = drag.entry.route.pushStyle == .photo ? 1 - p : 2 - p
            ch.content = 1 - p
        }
    }

    /// 놓음: 50% 이상이거나 빠르게 놓았으면(초당 1.5 이상) 확정, 아니면 되돌린다. 50%를 넘긴 뒤 반대로 빠르게 튕겨도
    /// 진행 50% 이상이면 확정한다(Android와 같은 규칙).
    func endDrag(progress: Double, velocity: Double) {
        guard let drag else { return }
        self.drag = nil
        let p = min(1, max(0, progress))
        let commit = p >= M.interactiveBackCommitProgress || velocity >= Self.flingProgressPerSecond
        if commit {
            pendingPopRemaining = 1 - p
            navigator.commitBackGesture() // activeTransition이 pop으로 바뀌면 handle이 남은 뒤로 모션을 재생한다.
            return
        }
        let ch = channels(drag.entry.id)
        let style = drag.entry.route.pushStyle
        let ms = scaled(style == .photo ? M.pushPhotoBack : M.pushSurfaceBack, p)
        withAnimation(C.emphasized.animation(ms: ms)) {
            ch.phase = style == .photo ? 1 : 2
            ch.content = 1
        } completion: { [weak self] in
            ch.animating = false
            if let key = drag.entry.sourceKey { self?.hiddenSources.remove(key) }
            self?.navigator.cancelBackGesture()
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
