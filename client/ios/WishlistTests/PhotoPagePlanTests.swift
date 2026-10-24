import XCTest
@testable import Wishlist

final class PhotoPagePlanTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000)
    private func items(_ range: ClosedRange<Int>, photo: Bool = true) -> [PhotoPagePlan.Item] {
        range.map { PhotoPagePlan.Item(id: "i\($0)", hasPhoto: photo) }
    }

    func testAPageWaitsUntilEverySizeIsKnown() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...3), now: t0)
        XCTAssertEqual(plan.waitingCount, 3)
        XCTAssertEqual(plan.sizesNeeded, ["i1", "i2", "i3"])
        plan.report("i1", size: CGSize(width: 100, height: 400), now: t0)
        plan.report("i2", size: nil, now: t0)
        XCTAssertNil(plan.ratio("i1"))
        plan.report("i3", size: CGSize(width: 300, height: 200), now: t0)
        XCTAssertEqual(plan.laidIds, ["i1", "i2", "i3"])
        XCTAssertEqual(plan.ratio("i1"), 4) // natural ratio, no clamp
        XCTAssertEqual(plan.ratio("i2"), 1) // failed → 1:1
        XCTAssertEqual(plan.ratio("i3")!, 2.0 / 3.0, accuracy: 0.0001)
    }

    func testAChangedPhotoStartsTheLayoutOverWithItsNewRatio() {
        var plan = PhotoPagePlan()
        plan.sync([PhotoPagePlan.Item(id: "i1", hasPhoto: true, photo: "https://a/tall.jpg")], now: t0)
        plan.report("i1", size: CGSize(width: 100, height: 400), now: t0)
        XCTAssertEqual(plan.ratio("i1"), 4)
        plan.sync([PhotoPagePlan.Item(id: "i1", hasPhoto: true, photo: "https://a/wide.jpg")], now: t0)
        XCTAssertNil(plan.ratio("i1"))
        XCTAssertEqual(plan.sizesNeeded, ["i1"])
        plan.report("i1", size: CGSize(width: 400, height: 100), now: t0)
        XCTAssertEqual(plan.ratio("i1"), 0.25)
    }

    func testCardsWithoutAPhotoNeedNoWait() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...2, photo: false), now: t0)
        XCTAssertEqual(plan.laidIds, ["i1", "i2"])
        XCTAssertEqual(plan.ratio("i1"), 1)
    }

    func testTimeoutLaysOutWithUnknownAsSquareAndLateSizesChangeNothing() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...2), now: t0)
        plan.report("i1", size: CGSize(width: 100, height: 150), now: t0)
        plan.tick(now: t0.addingTimeInterval(2.9))
        XCTAssertEqual(plan.waitingCount, 2)
        XCTAssertEqual(plan.nextDeadline, t0.addingTimeInterval(3))
        plan.tick(now: t0.addingTimeInterval(3))
        XCTAssertEqual(plan.ratio("i2"), 1)
        XCTAssertEqual(plan.ratio("i1"), 1.5)
        plan.report("i2", size: CGSize(width: 100, height: 500), now: t0.addingTimeInterval(4))
        XCTAssertEqual(plan.ratio("i2"), 1) // height never changes after layout
        XCTAssertNil(plan.nextDeadline)
    }

    func testAppendedPagesLayOutInOrder() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...2, photo: false), now: t0)
        plan.sync(items(1...2, photo: false) + items(3...4), now: t0)
        XCTAssertEqual(plan.waitingCount, 2)
        plan.sync(items(1...2, photo: false) + items(3...4) + items(5...6, photo: false), now: t0)
        // The third page has no photos but waits for the second.
        XCTAssertEqual(plan.laidIds, ["i1", "i2"])
        plan.report("i3", size: nil, now: t0)
        plan.report("i4", size: nil, now: t0)
        XCTAssertEqual(plan.laidIds, ["i1", "i2", "i3", "i4", "i5", "i6"])
    }

    func testARefreshStartsOver() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...3, photo: false), now: t0)
        plan.sync(items(7...8, photo: false), now: t0)
        XCTAssertEqual(plan.laidIds, ["i7", "i8"])
        XCTAssertEqual(plan.columns.flatMap { $0 }.sorted(), ["i7", "i8"])
    }

    func testColumnsTakeTheShorterOne() {
        var plan = PhotoPagePlan()
        plan.sync(items(1...3), now: t0)
        plan.report("i1", size: CGSize(width: 100, height: 300), now: t0) // tall
        plan.report("i2", size: CGSize(width: 100, height: 100), now: t0)
        plan.report("i3", size: CGSize(width: 100, height: 100), now: t0)
        XCTAssertEqual(plan.columns, [["i1"], ["i2", "i3"]])
    }

    func testDecodeCap() {
        XCTAssertEqual(PhotoPagePlan.maxPixels(columnPixels: 500), 750_000)
    }
}
