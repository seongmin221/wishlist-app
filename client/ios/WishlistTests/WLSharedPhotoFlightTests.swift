import SwiftUI
import XCTest
@testable import Wishlist

/// C5 PR B: the flight layer draws the card photo (source) on push and the detail's current photo (target)
/// on pop and drag back — also while a cancelled drag returns — then the other side, then the placeholder.
final class WLSharedPhotoFlightTests: XCTestCase {
    func testPushFliesTheCardPhotoAndBackTheDetailPhoto() {
        XCTAssertEqual(WLFlightPhoto.choose(back: false, hasSource: true, hasTarget: true), .source)
        XCTAssertEqual(WLFlightPhoto.choose(back: true, hasSource: true, hasTarget: true), .target)
    }

    func testAMissingSideFallsBackToTheOtherThenThePlaceholder() {
        XCTAssertEqual(WLFlightPhoto.choose(back: false, hasSource: false, hasTarget: true), .target)
        XCTAssertEqual(WLFlightPhoto.choose(back: true, hasSource: true, hasTarget: false), .source)
        XCTAssertEqual(WLFlightPhoto.choose(back: false, hasSource: false, hasTarget: false), .placeholder)
        XCTAssertEqual(WLFlightPhoto.choose(back: true, hasSource: false, hasTarget: false), .placeholder)
    }

    /// The detail registers what it draws under its target key while its probe is in a window.
    func testATargetProbeRegistersItsPhotoUntilItLeaves() {
        let registry = WLSharedRegistry()
        let key = WLSharedRegistry.targetKey(7)
        let probe = WLFrameProbe.ProbeView()
        probe.key = key
        probe.registry = registry
        probe.photo = { AnyView(Color.red) }
        probe.corner = 0
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        window.addSubview(probe)
        XCTAssertNotNil(registry.photo(key))
        XCTAssertEqual(registry.corner(key), 0, "the detail photo's square corner reaches the flight")

        probe.removeFromSuperview()
        XCTAssertNil(registry.photo(key))
        XCTAssertEqual(registry.corner(key), WishlistTokens.Radius.m)
    }

    /// A drag back marks the entry as going back (target photo) before any frame moves; a push does not.
    func testADragBackFliesTheDetailPhotoAndAPushTheCardPhoto() {
        let nav = WLNavigator()
        let motion = WLNavMotion(navigator: nav)
        motion.reduceMotion = true
        nav.push(WLRoute(destination: "product", pushStyle: .photo), sourceKey: "photo")
        let entry = nav.entries(.home).last!
        motion.handle(nav.activeTransition)
        XCTAssertFalse(motion.channels(entry.id).back)

        motion.reduceMotion = false // the drag prepares the flight only with motion on
        XCTAssertTrue(motion.beginDrag())
        XCTAssertTrue(motion.channels(entry.id).back)
    }
}
