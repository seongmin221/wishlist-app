import Observation
import SwiftUI
import UIKit
import XCTest
@testable import Wishlist

/// 머리 위 바텀시트(`WLHeaderSheet`): 위치 계산, 제어 요청, 머리 높이 변경 → resting 이동.
final class WLHeaderSheetTests: XCTestCase {
    func testProgressClampsBetweenRestingAndExpanded() {
        XCTAssertEqual(WLHeaderSheetMath.progress(offset: -30, travel: 160), 0)
        XCTAssertEqual(WLHeaderSheetMath.progress(offset: 40, travel: 160), 0.25)
        XCTAssertEqual(WLHeaderSheetMath.progress(offset: 400, travel: 160), 1)
        XCTAssertEqual(WLHeaderSheetMath.progress(offset: 40, travel: 0), 0)
    }

    func testSnapFollowsVelocityThenLastDirection() {
        // 두 멈춤 높이 사이: 속도 방향이 먼저, 속도가 없으면 마지막 스크롤 방향.
        XCTAssertEqual(WLHeaderSheetMath.snapTarget(y: 20, travel: 160, velocity: 0.8, expanding: false), 160)
        XCTAssertEqual(WLHeaderSheetMath.snapTarget(y: 140, travel: 160, velocity: -0.8, expanding: true), 0)
        XCTAssertEqual(WLHeaderSheetMath.snapTarget(y: 80, travel: 160, velocity: 0, expanding: true), 160)
        XCTAssertEqual(WLHeaderSheetMath.snapTarget(y: 80, travel: 160, velocity: 0, expanding: false), 0)
    }

    func testSheetTopFollowsScrollButStopsAtExpanded() {
        XCTAssertEqual(WLHeaderSheetMath.sheetTop(offset: 0, restingTop: 300, expandedTop: 120), 300)
        XCTAssertEqual(WLHeaderSheetMath.sheetTop(offset: -20, restingTop: 300, expandedTop: 120), 320)
        XCTAssertEqual(WLHeaderSheetMath.sheetTop(offset: 90, restingTop: 300, expandedTop: 120), 210)
        XCTAssertEqual(WLHeaderSheetMath.sheetTop(offset: 500, restingTop: 300, expandedTop: 120), 120)
    }

    func testSnapLeavesListScrollAndRubberBandAlone() {
        XCTAssertNil(WLHeaderSheetMath.snapTarget(y: 0, travel: 160, velocity: 0, expanding: true))
        XCTAssertNil(WLHeaderSheetMath.snapTarget(y: -20, travel: 160, velocity: -1, expanding: false))
        XCTAssertNil(WLHeaderSheetMath.snapTarget(y: 160, travel: 160, velocity: 0, expanding: false))
        XCTAssertNil(WLHeaderSheetMath.snapTarget(y: 500, travel: 160, velocity: -2, expanding: false))
    }

    func testAnimateRequestsAreDistinctEvenForSameDetent() {
        let state = WLHeaderSheetState()
        state.animate(to: .resting)
        let first = state.request
        state.animate(to: .resting)
        XCTAssertEqual(state.request?.detent, .resting)
        XCTAssertNotEqual(state.request, first)
        state.toggle()
        XCTAssertEqual(state.request?.detent, .expanded)
    }

    /// 머리가 길어지면(편집) resting이 측정 높이를 따라 내려가고, resting에 있던 시트도 함께 내려간다.
    func testRestingFollowsMeasuredHeaderHeight() {
        let model = WLHeaderSheetTestHeader()
        let box = WLHeaderSheetTestBox()
        let state = WLHeaderSheetState()
        let sheet = WLHeaderSheet(state: state, expandedTop: 100, barBackground: .clear,
                                  restingAnimation: .linear(duration: 0.05)) { _ in
            Color.clear.frame(height: model.height)
        } bar: { _ in
            Color.clear.frame(height: 1)
        } content: {
            Color.clear.frame(height: 10)
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { box.sheetContentTop = $0 }
        }
        let host = UIHostingController(rootView: sheet)
        host.safeAreaRegions = []
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first!
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true }

        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        XCTAssertEqual(state.restingTop, 200, accuracy: 0.5)
        XCTAssertEqual(state.travel, 100, accuracy: 0.5)
        // 시트 내용은 손잡이 줄 28 + 간격 12 아래에서 시작한다.
        XCTAssertEqual(box.sheetContentTop, 240, accuracy: 0.5)

        model.height = 440
        RunLoop.main.run(until: Date().addingTimeInterval(0.6))
        XCTAssertEqual(state.restingTop, 440, accuracy: 0.5)
        XCTAssertEqual(state.travel, 340, accuracy: 0.5)
        XCTAssertEqual(box.sheetContentTop, 480, accuracy: 0.5)
        XCTAssertEqual(state.detent, .resting)
    }
}

@Observable
private final class WLHeaderSheetTestHeader {
    var height: CGFloat = 200
}

private final class WLHeaderSheetTestBox {
    var sheetContentTop: CGFloat = .nan
}
