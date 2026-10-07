import CoreGraphics
import XCTest
@testable import Wishlist

final class SheetDragDecisionTests: XCTestCase {
    func testDismissesAtQuarterOfHeight() {
        XCTAssertTrue(shouldDismissSheet(dragDistance: 100, sheetHeight: 400, velocity: 0))
    }

    func testKeepsBelowThresholds() {
        XCTAssertFalse(shouldDismissSheet(dragDistance: 99, sheetHeight: 400, velocity: 999))
    }

    func testDismissesOnFastFling() {
        XCTAssertTrue(shouldDismissSheet(dragDistance: 10, sheetHeight: 400, velocity: 1000))
    }
}

final class SheetMaxHeightTests: XCTestCase {
    func testCapsAt760OnTallScreens() { XCTAssertEqual(sheetMaxHeight(available: 1000), 760) }
    func testLeavesTopMarginOnShortScreens() { XCTAssertEqual(sheetMaxHeight(available: 500), 500 - 24) }
    func testNeverNegative() { XCTAssertEqual(sheetMaxHeight(available: 10), 0) }
}

final class SheetDragEndTests: XCTestCase {
    func testDismissThenCancelledDragDoesNothing() {
        // 끄는 중 뒤로로 닫기가 시작되면 끌기가 속도 0으로 끝난다. 이때 되돌림이 나가면 안 된다.
        XCTAssertEqual(sheetDragEnd(phase: .closing, dragDistance: 10, sheetHeight: 400, velocity: 0), .ignore)
        XCTAssertEqual(sheetDragEnd(phase: .opening, dragDistance: 10, sheetHeight: 400, velocity: 0), .ignore)
    }

    func testOpenPhaseDecides() {
        XCTAssertEqual(sheetDragEnd(phase: .open, dragDistance: 100, sheetHeight: 400, velocity: 0), .dismiss)
        XCTAssertEqual(sheetDragEnd(phase: .open, dragDistance: 99, sheetHeight: 400, velocity: 999), .snapBack)
    }

    func testHostCompletesCloseAfterDismissDuringDrag() {
        let s = OverlayHostState()
        s.showSheet {}
        let e = s.entries[0]
        s.onOpened(e.id)
        XCTAssertTrue(s.dismiss())
        XCTAssertEqual(sheetDragEnd(phase: e.phase, dragDistance: 50, sheetHeight: 400, velocity: 0), .ignore)
        s.onClosed(e.id)
        XCTAssertFalse(s.isAnimating)
        XCTAssertTrue(s.showSheet {})
    }
}
