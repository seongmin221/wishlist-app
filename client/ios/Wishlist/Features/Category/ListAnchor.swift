import CoreGraphics
import Shared
import SwiftUI

/// C7 목록 anchor for the two-column card lists (category list, purpose candidates), pure. The screen
/// reports each drawn card's frame (global points) and asks for the first visible card when it refreshes
/// (`refresh(anchorItemId:)`, `foreground`). Each Presenter `AnchorRestore` token is accepted once and
/// applied only once its card is laid out (the page plan may still show skeletons right after a window
/// replace), or dropped when that card has not laid out within `restoreWait` (4 s, Android `RESTORE_WAIT_MS`);
/// a prepend (`loadNewer`) keeps the card that was first visible. Times are `systemUptime` seconds.
struct ListAnchorTracker {
    enum Target: Equatable {
        case top
        case item(String)
    }

    private var frames: [String: CGRect] = [:]
    private var lastToken: Int64?
    /// How long a restore waits for its card to lay out (the photo page plan gives up at 3 s).
    static let restoreWait: TimeInterval = 4

    private var pending: Target?
    private var pendingSince: TimeInterval = 0
    private var prependAnchor: String?
    private var prepending = false
    private var newerArmed = true

    mutating func report(_ id: String, _ frame: CGRect) { frames[id] = frame }
    mutating func remove(_ id: String) { frames.removeValue(forKey: id) }

    func frame(_ id: String) -> CGRect? { frames[id] }

    /// The topmost card still showing below `top` (the list's covered top: pinned header or sheet edge);
    /// on the same row the left column.
    func firstVisible(top: CGFloat) -> String? {
        frames.filter { $0.value.maxY > top }
            .min { a, b in a.value.minY != b.value.minY ? a.value.minY < b.value.minY : a.value.minX < b.value.minX }?.key
    }

    /// A new Presenter restore: true the first time its token is seen (then waits for `take`).
    mutating func accept(token: Int64, resolvedItemId: String?, now: TimeInterval = ListAnchorTracker.now) -> Bool {
        guard token != lastToken else { return false }
        lastToken = token
        setPending(resolvedItemId.map(Target.item) ?? .top, now: now)
        return true
    }

    /// `loadNewer` is starting: the card first visible now is where the list stays once newer cards land.
    mutating func beginPrepend(top: CGFloat) {
        prependAnchor = firstVisible(top: top)
        prepending = prependAnchor != nil
    }

    /// Whether to call `loadNewer` now: at the top edge with newer cards and nothing in flight, once per
    /// visit to the top (a failed prepend waits until the list has left the top and come back, Ruling 9).
    mutating func shouldLoadNewer(atTop: Bool, hasNewer: Bool, busy: Bool) -> Bool {
        guard atTop else { newerArmed = true; return false }
        guard newerArmed, hasNewer, !busy else { return false }
        newerArmed = false
        return true
    }

    /// The newer cards landed in front (a Presenter restore still waiting wins over it).
    mutating func prependLanded(now: TimeInterval = ListAnchorTracker.now) {
        guard prepending, let anchor = prependAnchor else { return }
        expire(now: now)
        if pending == nil { setPending(.item(anchor), now: now) }
        prepending = false
        prependAnchor = nil
    }

    /// The prepend ended without new cards (failed or nothing newer).
    mutating func cancelPrepend() {
        prepending = false
        prependAnchor = nil
    }

    /// The scroll to make now, once: `.top`, or the card when it is laid out.
    mutating func take(laidIds: [String], now: TimeInterval = ListAnchorTracker.now) -> Target? {
        expire(now: now)
        guard let target = pending else { return nil }
        if case .item(let id) = target, !laidIds.contains(id) { return nil }
        pending = nil
        return target
    }

    static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    private mutating func setPending(_ target: Target, now: TimeInterval) {
        pending = target
        pendingSince = now
    }

    /// A card restore still waiting after `restoreWait` is dropped (`.top` needs no layout and never waits).
    private mutating func expire(now: TimeInterval) {
        if case .item = pending, now - pendingSince >= Self.restoreWait { pending = nil }
    }
}

enum ListAnchorMath {
    /// Inside a header sheet, a restore leaves the sheet alone only when its card is the list's first and the
    /// list already sits at its top. A first card with the list scrolled down (cards in front were removed
    /// elsewhere) still scrolls, or another card or the list's end would stay in view.
    static func sheetRestoreScrolls(to id: String, firstItemId: String?, listAtTop: Bool) -> Bool {
        !(id == firstItemId && listAtTop)
    }

    /// `ScrollViewProxy.scrollTo(_:anchor:)` lines up the item's point at fraction y with the viewport's
    /// point at the same fraction, so the item's top lands at `y × (viewport − item)`. This y puts the top at
    /// `topInset` (0 when the card is taller than the room left).
    static func anchor(topInset: CGFloat, viewportHeight: CGFloat, itemHeight: CGFloat) -> UnitPoint {
        let room = viewportHeight - itemHeight
        guard room > topInset, room > 0 else { return UnitPoint(x: 0.5, y: 0) }
        return UnitPoint(x: 0.5, y: topInset / room)
    }

    /// Scrolls the card in first (a lazy column draws it, and so reports its real height, only once it is
    /// near), then on the next frames puts its top right under the covered top.
    @MainActor
    static func scroll(_ proxy: ScrollViewProxy, to id: String, box: ListAnchorBox) {
        proxy.scrollTo(id, anchor: .top)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(50))
            let height = box.tracker.frame(id)?.height ?? 0
            proxy.scrollTo(id, anchor: anchor(topInset: box.topInset, viewportHeight: box.viewportHeight, itemHeight: height))
        }
    }
}

/// The screen's tracker behind a reference (`@State`): frames change on every scroll frame, and keeping
/// them out of observed state keeps scrolling from redrawing the list.
@MainActor
final class ListAnchorBox {
    var tracker = ListAnchorTracker()
    /// The list's first item when `loadNewer` started (a prepend that changed it landed).
    var prependFirst: String?
    /// Global y where the list's visible area starts (under a pinned header or the sheet edge).
    var coveredTop: CGFloat = 0
    /// Height covered at the top of the scroll view's own viewport (where a restored card's top goes).
    var topInset: CGFloat = 0
    var viewportHeight: CGFloat = 0

    func firstVisible(top: CGFloat) -> String? { tracker.firstVisible(top: top) }

    func shouldLoadNewer(atTop: Bool, hasNewer: Bool, busy: Bool) -> Bool {
        tracker.shouldLoadNewer(atTop: atTop, hasNewer: hasNewer, busy: busy)
    }

    func beginPrepend(top: CGFloat, firstItemId: String?) {
        prependFirst = firstItemId
        tracker.beginPrepend(top: top)
    }
}

/// Applies the Presenter's `AnchorRestore` once per token (after its card is laid out) and keeps the first
/// visible card after a prepend. `apply` scrolls: `.top`, or the card to the list's covered top.
struct ListAnchorWiring: ViewModifier {
    let box: ListAnchorBox
    let restore: AnchorRestore?
    let laidIds: [String]
    let firstItemId: String?
    let prepending: Bool
    let apply: (ListAnchorTracker.Target) -> Void

    func body(content: Content) -> some View {
        content
            .onChange(of: restore?.token) { _, _ in
                guard let restore, box.tracker.accept(token: restore.token, resolvedItemId: restore.resolvedItemId) else { return }
                drain()
            }
            .onChange(of: laidIds) { _, _ in drain() }
            .onChange(of: prepending) { was, now in
                guard was, !now else { return }
                if firstItemId != box.prependFirst { box.tracker.prependLanded() } else { box.tracker.cancelPrepend() }
                drain()
            }
    }

    private func drain() {
        if let target = box.tracker.take(laidIds: laidIds) { apply(target) }
    }
}

extension View {
    /// Reports this card's global frame to the list's anchor tracker (and forgets it when the card goes).
    func listAnchorFrame(_ id: String, into box: ListAnchorBox) -> some View {
        onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { box.tracker.report(id, $0) }
            .onDisappear { box.tracker.remove(id) }
    }
}
