import XCTest
@testable import Wishlist

/// The review card release rule and drag rotation (Android `CardThrowMathTest`, same values): throw at
/// `|dx| ≥ W × 0.3` or `|vx| ≥ 800/s` in that direction, otherwise return; rotation = dx / W × 16°.
final class CardThrowMathTests: XCTestCase {
    private let width: CGFloat = 390

    func testThirtyPercentOfTheWidthThrowsRight() {
        XCTAssertEqual(CardThrowMath.decide(dx: width * 0.3, velocityX: 0, width: width), .right)
    }

    func testJustUnderBothThresholdsReturns() {
        XCTAssertEqual(CardThrowMath.decide(dx: width * 0.29, velocityX: 799, width: width), .return)
        XCTAssertEqual(CardThrowMath.decide(dx: -width * 0.29, velocityX: -799, width: width), .return)
    }

    func testAFlingLeftThrowsLeftWithoutDistance() {
        XCTAssertEqual(CardThrowMath.decide(dx: 0, velocityX: -800, width: width), .left)
        XCTAssertEqual(CardThrowMath.decide(dx: -width * 0.3, velocityX: 0, width: width), .left)
    }

    /// A fling decides the direction even against the distance (the finger flicked back).
    func testAFlingWinsOverTheDistance() {
        XCTAssertEqual(CardThrowMath.decide(dx: width * 0.4, velocityX: -900, width: width), .left)
    }

    func testRotationFollowsTheDrag() {
        XCTAssertEqual(CardThrowMath.rotation(dx: width / 2, width: width), 8, accuracy: 0.0001)
        XCTAssertEqual(CardThrowMath.rotation(dx: -width, width: width), -16, accuracy: 0.0001)
        XCTAssertEqual(CardThrowMath.rotation(dx: 10, width: 0), 0)
    }

    /// A throw ends with a pass only for the card that was thrown: a foreground rebuild that replaced the
    /// current card meanwhile (or emptied it) does not pass the new one.
    func testOnlyTheThrownCardIsPassed() {
        XCTAssertTrue(ReviewStackModel.shouldPass(currentID: "a", leavingID: "a"))
        XCTAssertFalse(ReviewStackModel.shouldPass(currentID: "b", leavingID: "a"))
        XCTAssertFalse(ReviewStackModel.shouldPass(currentID: nil, leavingID: "a"))
    }

    /// While a card leaves, the next one is already in front with one back card fewer.
    func testStackFrontAndBacksDuringAThrow() {
        XCTAssertEqual(ReviewStackModel.backs(position: 1, total: 5, frontIsNext: false), 2)
        XCTAssertEqual(ReviewStackModel.backs(position: 1, total: 3, frontIsNext: true), 1)
        XCTAssertEqual(ReviewStackModel.backs(position: 3, total: 3, frontIsNext: false), 0)
    }

    /// A released drag continues the 340 throw for the distance left to ±480.
    func testExitTimeIsTheRemainingPartOfTheThrow() {
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 0), 340)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: -240), 170)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 600), CardThrowMath.minExitMillis)
    }
}
