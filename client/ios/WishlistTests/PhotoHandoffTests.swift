import XCTest
@testable import Wishlist

final class PhotoHandoffTests: XCTestCase {
    private let card = UIImage()

    func testHeldCardPhotoReachesTheDetail() {
        let handoff = PhotoHandoff()
        handoff.hold(7, card)

        XCTAssertTrue(handoff.forward(7) === card)
        XCTAssertNil(handoff.forward(8))
    }

    func testReleasedOnlyAfterTheDetailPhotoIsDrawnAndTheMotionEnded() {
        let handoff = PhotoHandoff()
        handoff.hold(7, card)

        handoff.detailDrawn(7)
        XCTAssertNotNil(handoff.forward(7), "still flying")
        handoff.motionEnded(7)
        XCTAssertNil(handoff.forward(7))
    }

    func testMotionEndingFirstWaitsForTheDetailPhoto() {
        let handoff = PhotoHandoff()
        handoff.hold(7, card)

        handoff.motionEnded(7)
        XCTAssertNotNil(handoff.forward(7), "the detail photo is not drawn yet")
        handoff.detailDrawn(7)
        XCTAssertNil(handoff.forward(7))
    }

    func testRemovedEntriesReleaseTheirPhoto() {
        let handoff = PhotoHandoff()
        handoff.hold(7, card)
        handoff.hold(9, card)

        handoff.drop([7])

        XCTAssertNil(handoff.forward(7))
        XCTAssertNotNil(handoff.forward(9))
    }

    func testLettingGoReleasesTheHeldPhotoAndItsAddress() {
        let handoff = PhotoHandoff()
        let a = URL(string: "https://img/a")!
        handoff.hold(7, card, sourceURL: a)
        XCTAssertEqual(handoff.sourceURL(7), a)

        handoff.letGo(7)

        XCTAssertNil(handoff.forward(7))
        XCTAssertNil(handoff.sourceURL(7))
    }
}
