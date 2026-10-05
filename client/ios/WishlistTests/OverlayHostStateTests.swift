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

    private func dialog(onConfirm: @escaping () -> Void) -> WLDialogSpec {
        WLDialogSpec(title: "t", bullets: [], cancelText: "취소", confirmText: "확인", confirmKind: .primary, onConfirm: onConfirm)
    }

    func testConfirmThenDismissAllClosesDialogAndSheetBelow() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s)
        var confirmed = 0
        s.showDialog(dialog { confirmed += 1; XCTAssertTrue(s.dismissAll()) }); settleOpen(s)
        let id = s.entries[1].id
        XCTAssertTrue(s.confirm(id))
        XCTAssertEqual(confirmed, 1)
        XCTAssertFalse(s.confirm(id))   // 두 번째 확인은 무시
        XCTAssertEqual(s.entries.map(\.phase), [.open, .closing])
        settleClose(s)                  // 확인창 제거 -> 시트 닫기 시작
        XCTAssertEqual(s.entries.count, 1)
        if case .sheet = s.entries[0].kind {} else { XCTFail("시트가 남아야 한다") }
        XCTAssertEqual(s.entries[0].phase, .closing)
        settleClose(s)
        XCTAssertTrue(s.entries.isEmpty)
        XCTAssertFalse(s.isAnimating)
        // 끝난 뒤에는 평소처럼 하나씩 닫는다.
        s.showSheet {}; settleOpen(s); s.showDialog(spec); settleOpen(s)
        XCTAssertTrue(s.dismiss()); settleClose(s)
        XCTAssertEqual(s.entries.map(\.phase), [.open])
    }

    func testDismissInsideOnConfirmIsStillIgnored() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s)
        s.showDialog(dialog { XCTAssertFalse(s.dismiss()) }); settleOpen(s)
        XCTAssertTrue(s.confirm(s.entries[1].id))
        settleClose(s)
        XCTAssertEqual(s.entries.map(\.phase), [.open])
    }

    func testDismissAllFromOpenStackClosesTopFirst() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s); s.showDialog(spec); settleOpen(s)
        XCTAssertTrue(s.dismissAll())
        XCTAssertEqual(s.entries.map(\.phase), [.open, .closing])
        settleClose(s); settleClose(s)
        XCTAssertTrue(s.entries.isEmpty)
    }

    func testDismissAllIgnoredWhileOpeningOrEmpty() {
        let s = OverlayHostState()
        XCTAssertFalse(s.dismissAll())
        s.showSheet {}
        XCTAssertFalse(s.dismissAll())
        XCTAssertEqual(s.entries.map(\.phase), [.opening])
    }

    func testDismissAllDropsEarlierQueuedShow() {
        let s = OverlayHostState()
        s.showSheet {}; settleOpen(s); s.showMenu(anchor: .zero, items: []); settleOpen(s)
        s.dismiss()
        s.showDialog(spec)              // 줄 세움
        XCTAssertTrue(s.dismissAll())
        settleClose(s); settleClose(s)
        XCTAssertTrue(s.entries.isEmpty)
    }
}
