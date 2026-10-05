import CoreGraphics
import XCTest
@testable import Wishlist

final class OverlayHostStateTests: XCTestCase {
    private let spec = WLDialogSpec(title: "t", bullets: [], cancelText: "취소", confirmText: "확인", confirmKind: .primary, onConfirm: {})

    private func settleOpen(_ s: OverlayHostState) {
        s.entries.filter { $0.phase == .opening }.forEach { s.onOpened($0.id) }
    }

    private func settleClose(_ s: OverlayHostState) {
        s.entries.filter { $0.phase == .closing }.forEach { s.onClosed($0.id) }
    }

    private func isDialog(_ e: OverlayEntry) -> Bool {
        if case .dialog = e.kind { return true }
        return false
    }

    func testShowStartsOpeningAndBlocksInput() {
        let s = OverlayHostState()
        XCTAssertTrue(s.showSheet {})
        XCTAssertTrue(s.isAnimating)
        XCTAssertEqual(s.entries.count, 1)
        XCTAssertEqual(s.entries[0].phase, .opening)
        settleOpen(s)
        XCTAssertFalse(s.isAnimating)
    }

    func testDismissDuringOpenIsIgnored() {
        let s = OverlayHostState()
        s.showSheet {}
        XCTAssertFalse(s.dismiss())
        XCTAssertEqual(s.entries[0].phase, .opening)
    }

    func testRepeatedDismissClosesOnce() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s)
        XCTAssertTrue(s.dismiss())
        XCTAssertFalse(s.dismiss())
        XCTAssertFalse(s.dismiss())
        settleClose(s)
        XCTAssertTrue(s.entries.isEmpty)
        XCTAssertFalse(s.isAnimating)
    }

    func testShowDuringOpenIsIgnored() {
        let s = OverlayHostState()
        s.showSheet {}
        XCTAssertFalse(s.showSheet {})
        XCTAssertFalse(s.showDialog(spec))
        XCTAssertEqual(s.entries.count, 1)
    }

    func testShowDuringCloseIsQueuedAndRunsAfterClose() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s); s.dismiss()
        XCTAssertTrue(s.showDialog(spec))
        XCTAssertEqual(s.entries.count, 1)
        XCTAssertEqual(s.entries[0].phase, .closing)
        settleClose(s)
        XCTAssertEqual(s.entries.count, 1)
        XCTAssertTrue(isDialog(s.entries[0]))
        XCTAssertEqual(s.entries[0].phase, .opening)
    }

    func testLaterQueuedShowReplacesEarlier() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s); s.dismiss()
        s.showSheet {}
        s.showDialog(spec)
        settleClose(s)
        XCTAssertEqual(s.entries.count, 1)
        XCTAssertTrue(isDialog(s.entries[0]))
    }

    func testDismissClosesTopMostOnly() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s)
        s.showDialog(spec); settleOpen(s)
        XCTAssertEqual(s.entries.count, 2)
        XCTAssertTrue(s.dismiss())
        XCTAssertEqual(s.entries[0].phase, .open)
        XCTAssertEqual(s.entries[1].phase, .closing)
        settleClose(s)
        XCTAssertEqual(s.entries.count, 1)
        XCTAssertTrue(s.dismiss())
    }

    func testMenuOpensAndDismissesLikeOthers() {
        let s = OverlayHostState()
        XCTAssertTrue(s.showMenu(anchor: CGRect(x: 0, y: 0, width: 10, height: 10), items: [WLMenuItem(text: "편집", onClick: {})]))
        settleOpen(s)
        XCTAssertTrue(s.dismiss())
        settleClose(s)
        XCTAssertTrue(s.entries.isEmpty)
    }

    func testConfirmRunsOnceAfterCloseStarts() {
        var confirmed = 0
        let s = OverlayHostState()
        s.showDialog(WLDialogSpec(title: "t", bullets: [], cancelText: "취소", confirmText: "확인", confirmKind: .danger, onConfirm: { confirmed += 1 }))
        let id = s.entries[0].id
        XCTAssertFalse(s.confirm(id))   // 열리는 중에는 확인이 무시된다
        XCTAssertEqual(confirmed, 0)
        settleOpen(s)
        XCTAssertTrue(s.confirm(id))
        XCTAssertFalse(s.confirm(id))   // 닫히는 중 연타는 무시
        XCTAssertEqual(confirmed, 1)
    }
}
