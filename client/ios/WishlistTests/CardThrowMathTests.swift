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

    /// Ruling 10: the pass is recorded when the throw starts (the counter moves at once), and only for the card
    /// shown then: a foreground rebuild that already replaced (or emptied) the current card passes nothing.
    func testOnlyTheThrownCardIsPassed() {
        XCTAssertTrue(ReviewStackModel.shouldPass(currentID: "a", leavingID: "a"))
        XCTAssertFalse(ReviewStackModel.shouldPass(currentID: "b", leavingID: "a"))
        XCTAssertFalse(ReviewStackModel.shouldPass(currentID: nil, leavingID: "a"))
    }

    /// The pass happens at the start, so the stack stays drawn while the last card is still leaving over the
    /// summary (the summary rises under it, as on Android); without a leaving card it follows the phase.
    func testTheStackStaysWhileTheLastCardLeaves() {
        XCTAssertTrue(ReviewStackModel.showsStack(done: true, hasCurrent: false, leaving: true))
        XCTAssertFalse(ReviewStackModel.showsStack(done: true, hasCurrent: false, leaving: false))
        XCTAssertTrue(ReviewStackModel.showsStack(done: false, hasCurrent: true, leaving: false))
        XCTAssertTrue(ReviewStackModel.showsStack(done: false, hasCurrent: true, leaving: true))
        XCTAssertFalse(ReviewStackModel.showsStack(done: false, hasCurrent: false, leaving: false))
    }

    /// After the pass the Presenter's current card is the next one: the leaving card is never drawn as the
    /// front, and the front is the new current (no back card is counted twice).
    func testAfterThePassTheFrontIsTheNewCurrent() {
        XCTAssertEqual(ReviewStackModel.backs(position: 2, total: 3, frontIsNext: false), 1)
        XCTAssertEqual(ReviewStackModel.backs(position: 1, total: 3, frontIsNext: true), 1)
    }

    /// While a card leaves, the next one is already in front with one back card fewer.
    func testStackFrontAndBacksDuringAThrow() {
        XCTAssertEqual(ReviewStackModel.backs(position: 1, total: 5, frontIsNext: false), 2)
        XCTAssertEqual(ReviewStackModel.backs(position: 1, total: 3, frontIsNext: true), 1)
        XCTAssertEqual(ReviewStackModel.backs(position: 3, total: 3, frontIsNext: false), 0)
    }

    /// A released drag continues the 340 throw for the distance left to ±480 (truncated, no floor: the same
    /// as Android `remainingMillis`).
    func testExitTimeIsTheRemainingPartOfTheThrow() {
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 0), 340)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: -240), 170)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 10), 332)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 480), 0)
        XCTAssertEqual(CardThrowMath.exitMillis(fromDx: 600), 0)
    }
}
