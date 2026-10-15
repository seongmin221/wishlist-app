import Shared
import XCTest
@testable import Wishlist

final class DetailSharedPhotoTests: XCTestCase {
    /// One photo slot from the first frame (item still loading) to the shown product, so the flight's target
    /// probe is there when the push measures it and stays the same view (Android `keepsSharedPhotoSlot`).
    func testAPhotoEntryKeepsOneSharedSlotFromLoadingToTheProduct() {
        XCTAssertTrue(DetailSharedSlot.keeps(photoEntry: true, hasItem: false, hasError: false, kind: nil))
        XCTAssertTrue(DetailSharedSlot.keeps(photoEntry: true, hasItem: true, hasError: false, kind: .ready))
        XCTAssertTrue(DetailSharedSlot.keeps(photoEntry: true, hasItem: true, hasError: true, kind: .incomplete))
        XCTAssertFalse(DetailSharedSlot.keeps(photoEntry: true, hasItem: false, hasError: true, kind: nil))
        XCTAssertFalse(DetailSharedSlot.keeps(photoEntry: true, hasItem: true, hasError: false, kind: .processing))
        XCTAssertFalse(DetailSharedSlot.keeps(photoEntry: true, hasItem: true, hasError: false, kind: .gone))
        XCTAssertFalse(DetailSharedSlot.keeps(photoEntry: false, hasItem: true, hasError: false, kind: .ready))
    }

    /// What the slot draws: the detail-size photo, else the held card photo, else the biggest cached one.
    func testTheSlotDrawsTheDetailPhotoThenTheHeldThenTheCachedOne() {
        let detail = UIImage(), held = UIImage(), cached = UIImage()
        XCTAssertTrue(DetailSharedSlot.shown(loaded: detail, held: held, cached: { cached }) === detail)
        XCTAssertTrue(DetailSharedSlot.shown(loaded: nil, held: held, cached: { cached }) === held)
        XCTAssertTrue(DetailSharedSlot.shown(loaded: nil, held: nil, cached: { cached }) === cached)
        XCTAssertNil(DetailSharedSlot.shown(loaded: nil, held: nil, cached: { nil }))
    }
}
