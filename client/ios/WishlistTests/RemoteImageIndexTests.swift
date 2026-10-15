import XCTest
@testable import Wishlist

/// C5 PR B: the memory cache keeps a per-address index so the detail can draw the biggest photo it has
/// for that address from the first frame (`bestCached(for:)`), and the index follows evictions.
final class RemoteImageIndexTests: XCTestCase {
    private func file(width: CGFloat, height: CGFloat) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
        try RemoteImageTests.png(width: width, height: height).write(to: url)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    func testBestCachedIsTheLargestMemoryPhotoOfThatAddress() async throws {
        let url = try file(width: 1000, height: 1000)
        let other = try file(width: 400, height: 400)
        let loader = RemoteImageLoader()
        XCTAssertNil(loader.bestCached(for: url))

        _ = await loader.image(for: url, frame: CGSize(width: 50, height: 50), scale: 1, fill: true)
        _ = await loader.image(for: url, width: 200, scale: 1, maxPixels: 200 * 200 * 3)
        _ = await loader.image(for: url, frame: CGSize(width: 100, height: 100), scale: 1, fill: true)

        let best = try XCTUnwrap(loader.bestCached(for: url)?.cgImage)
        XCTAssertEqual(best.width, 200)
        XCTAssertNil(loader.bestCached(for: other))
    }

    func testClearingTheMemoryEmptiesTheIndex() async throws {
        let url = try file(width: 300, height: 300)
        let loader = RemoteImageLoader()
        _ = await loader.image(for: url, frame: CGSize(width: 100, height: 100), scale: 1, fill: true)

        loader.clearMemory()

        XCTAssertNil(loader.bestCached(for: url))
        XCTAssertEqual(loader.indexedKeyCount(for: url), 0)
    }

    func testAnEvictedPhotoLeavesTheIndex() async throws {
        let small = try file(width: 100, height: 100)
        let big = try file(width: 400, height: 400)
        // Room for the big photo only (400 × 400 × 4 bytes): adding it evicts the small one.
        let loader = RemoteImageLoader(memoryCostLimit: 400 * 400 * 4)
        _ = await loader.image(for: small, frame: CGSize(width: 100, height: 100), scale: 1, fill: true)
        XCTAssertEqual(loader.indexedKeyCount(for: small), 1)

        _ = await loader.image(for: big, frame: CGSize(width: 400, height: 400), scale: 1, fill: true)

        XCTAssertNil(loader.bestCached(for: small))
        XCTAssertEqual(loader.indexedKeyCount(for: small), 0)
        XCTAssertNotNil(loader.bestCached(for: big))
    }
}
