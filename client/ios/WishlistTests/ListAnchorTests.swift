import CoreGraphics
import XCTest
@testable import Wishlist

/// C7 list anchor on iOS: the first visible card (handed to `refresh(anchorItemId:)`/`foreground`), each
/// `AnchorRestore` token applied once and only after its card is laid out, and the scroll anchor that puts
/// a card's top right under the list's covered top.
final class ListAnchorTests: XCTestCase {
    func testFirstVisibleIsTheTopmostCardBelowTheCoveredTopLeftColumnFirst() {
        var tracker = ListAnchorTracker()
        tracker.report("a", CGRect(x: 16, y: -300, width: 170, height: 240)) // scrolled away
        tracker.report("b", CGRect(x: 198, y: -120, width: 170, height: 260)) // still under the header
        tracker.report("c", CGRect(x: 16, y: 120, width: 170, height: 200))
        tracker.report("d", CGRect(x: 198, y: 160, width: 170, height: 200))
        XCTAssertEqual(tracker.firstVisible(top: 100), "b")
        tracker.report("e", CGRect(x: 16, y: 140, width: 170, height: 200))
        tracker.remove("b")
        XCTAssertEqual(tracker.firstVisible(top: 100), "c")
        tracker.report("f", CGRect(x: 198, y: 120, width: 170, height: 200))
        XCTAssertEqual(tracker.firstVisible(top: 100), "c") // same row: the left column
        XCTAssertNil(ListAnchorTracker().firstVisible(top: 0))
    }

    func testEachRestoreTokenIsAppliedOnceAfterItsCardIsLaidOut() {
        var tracker = ListAnchorTracker()
        XCTAssertTrue(tracker.accept(token: 1, resolvedItemId: "x"))
        XCTAssertNil(tracker.take(laidIds: ["a"])) // not laid out yet
        XCTAssertEqual(tracker.take(laidIds: ["a", "x"]), .item("x"))
        XCTAssertNil(tracker.take(laidIds: ["a", "x"])) // applied once
        XCTAssertFalse(tracker.accept(token: 1, resolvedItemId: "x")) // same token again
        XCTAssertTrue(tracker.accept(token: 2, resolvedItemId: nil))
        XCTAssertEqual(tracker.take(laidIds: []), .top)
    }

    func testAPrependKeepsTheCardThatWasFirstVisible() {
        var tracker = ListAnchorTracker()
        tracker.report("c", CGRect(x: 16, y: 120, width: 170, height: 200))
        tracker.beginPrepend(top: 100)
        tracker.prependLanded()
        XCTAssertEqual(tracker.take(laidIds: ["n1", "n2", "c"]), .item("c"))
        tracker.beginPrepend(top: 100)
        tracker.cancelPrepend()
        tracker.prependLanded()
        XCTAssertNil(tracker.take(laidIds: ["c"]))
    }

    /// Ruling 9: one `loadNewer` per visit to the top edge; a failed prepend (hasNewer stays) waits until the
    /// list has left the top and come back, and nothing fires while a read is in flight.
    func testLoadNewerFiresOncePerVisitToTheTop() {
        var tracker = ListAnchorTracker()
        XCTAssertFalse(tracker.shouldLoadNewer(atTop: true, hasNewer: false, busy: false))
        XCTAssertTrue(tracker.shouldLoadNewer(atTop: true, hasNewer: true, busy: false))
        XCTAssertFalse(tracker.shouldLoadNewer(atTop: true, hasNewer: true, busy: true)) // in flight
        XCTAssertFalse(tracker.shouldLoadNewer(atTop: true, hasNewer: true, busy: false)) // failed: no loop
        XCTAssertFalse(tracker.shouldLoadNewer(atTop: false, hasNewer: true, busy: false)) // left the top: re-armed
        XCTAssertTrue(tracker.shouldLoadNewer(atTop: true, hasNewer: true, busy: false))
    }

    /// A Presenter restore waiting for its card wins over a prepend that lands meanwhile.
    func testAPrependDoesNotOverrideAPendingRestore() {
        var tracker = ListAnchorTracker()
        tracker.report("c", CGRect(x: 16, y: 120, width: 170, height: 200))
        tracker.beginPrepend(top: 100)
        XCTAssertTrue(tracker.accept(token: 7, resolvedItemId: "r"))
        tracker.prependLanded()
        XCTAssertNil(tracker.take(laidIds: ["c"]))
        XCTAssertEqual(tracker.take(laidIds: ["c", "r"]), .item("r"))
    }

    func testScrollAnchorPutsTheCardTopUnderTheCoveredTop() {
        let anchor = ListAnchorMath.anchor(topInset: 100, viewportHeight: 700, itemHeight: 200)
        // scrollTo aligns the item's point at y-fraction f with the viewport's: item top = f × (700 − 200).
        XCTAssertEqual(anchor.y * (700 - 200), 100, accuracy: 0.0001)
        XCTAssertEqual(ListAnchorMath.anchor(topInset: 100, viewportHeight: 300, itemHeight: 400).y, 0) // taller than the room
    }
}
