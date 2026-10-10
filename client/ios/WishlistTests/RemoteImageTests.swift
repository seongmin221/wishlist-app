import XCTest
@testable import Wishlist

final class RemoteImageTests: XCTestCase {
    private func writePNG(side: CGFloat, to url: URL) throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
        }
        try img.pngData()!.write(to: url)
    }

    private func tempURL(_ name: String) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString)-\(name)")
    }

    func testDownsamplesToTheRequestedPixelSize() async throws {
        let url = tempURL("big.png")
        try writePNG(side: 1200, to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let image = await RemoteImageLoader.shared.image(for: url, maxPixel: 300)
        let cg = try XCTUnwrap(image?.cgImage)
        XCTAssertEqual(max(cg.width, cg.height), 300)
    }

    func testInvalidDataReturnsNil() async throws {
        let url = tempURL("bad.png")
        try Data("not an image".utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let image = await RemoteImageLoader.shared.image(for: url, maxPixel: 300)
        XCTAssertNil(image)
    }

    func testRemotePhotoURLOnlyAcceptsHttpWithHost() {
        XCTAssertNil(remotePhotoURL(nil))
        XCTAssertNil(remotePhotoURL("not a url"))
        XCTAssertNil(remotePhotoURL("https://"))
        XCTAssertNil(remotePhotoURL("file:///a.png"))
        XCTAssertNotNil(remotePhotoURL(" https://img.example.com/a.png "))
    }
}
