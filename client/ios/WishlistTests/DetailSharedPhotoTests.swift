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

    /// A loaded detail photo counts only for the address it was loaded from (a refresh that removes or replaces it).
    func testALoadedPhotoIsDroppedWhenThePhotoAddressChangesOrGoes() {
        let a = URL(string: "https://img/a")!, b = URL(string: "https://img/b")!, image = UIImage()
        XCTAssertTrue(DetailSharedSlot.loaded(for: a, (a, image)) === image)
        XCTAssertNil(DetailSharedSlot.loaded(for: nil, (a, image)))
        XCTAssertNil(DetailSharedSlot.loaded(for: b, (a, image)))
        XCTAssertNil(DetailSharedSlot.loaded(for: a, nil))
    }

    /// The held card photo stands for the item only while the item is loading or shows the address the card
    /// drew: a refresh that removes or replaces the photo drops it even when the detail photo never loaded.
    func testTheHeldCardPhotoFitsOnlyTheAddressTheCardDrew() {
        let a = URL(string: "https://img/a")!, b = URL(string: "https://img/b")!
        XCTAssertTrue(DetailSharedSlot.heldFits(itemLoaded: false, heldURL: a, url: nil))
        XCTAssertTrue(DetailSharedSlot.heldFits(itemLoaded: true, heldURL: a, url: a))
        XCTAssertFalse(DetailSharedSlot.heldFits(itemLoaded: true, heldURL: a, url: nil))
        XCTAssertFalse(DetailSharedSlot.heldFits(itemLoaded: true, heldURL: a, url: b))
    }
}
