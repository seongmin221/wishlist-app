import XCTest
@testable import Wishlist

final class RemoteImageTests: XCTestCase {
    private func writePNG(side: CGFloat, to url: URL) throws {
        try writePNG(width: side, height: side, to: url)
    }

    private func writePNG(width: CGFloat, height: CGFloat, to url: URL) throws {
        try Self.png(width: width, height: height).write(to: url)
    }

    static func png(width: CGFloat, height: CGFloat) -> Data {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        return img.pngData()!
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

    /// Fill covers the frame (the image's short side meets the frame's long side), fit fits inside it;
    /// never above the source's own long side.
    func testMaxPixelCoversTheFrameForFillAndFit() {
        let wide = CGSize(width: 2000, height: 1000)
        let frame = CGSize(width: 100, height: 100)
        XCTAssertEqual(RemoteImageLoader.maxPixel(source: wide, frame: frame, scale: 2, fill: true), 400)
        XCTAssertEqual(RemoteImageLoader.maxPixel(source: wide, frame: frame, scale: 2, fill: false), 200)
        XCTAssertEqual(RemoteImageLoader.maxPixel(source: CGSize(width: 1000, height: 2000), frame: CGSize(width: 300, height: 100), scale: 1, fill: true), 600)
        XCTAssertEqual(RemoteImageLoader.maxPixel(source: wide, frame: CGSize(width: 5000, height: 5000), scale: 3, fill: true), 2000)
        XCTAssertEqual(RemoteImageLoader.maxPixel(source: .zero, frame: frame, scale: 2, fill: true), 0)
    }

    func testFrameLoadKeepsAWideImageSharpWhenFilling() async throws {
        let url = tempURL("wide.png")
        try writePNG(width: 1200, height: 600, to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let loader = RemoteImageLoader()
        let fillImage = await loader.image(for: url, frame: CGSize(width: 100, height: 100), scale: 1, fill: true)
        let filled = try XCTUnwrap(fillImage?.cgImage)
        XCTAssertEqual(filled.width, 200)
        XCTAssertEqual(filled.height, 100)
        let fitImage = await loader.image(for: url, frame: CGSize(width: 100, height: 100), scale: 1, fill: false)
        let fitted = try XCTUnwrap(fitImage?.cgImage)
        XCTAssertEqual(fitted.width, 100)
    }

    func testZeroFrameDoesNotLoad() async throws {
        CountingImageProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CountingImageProtocol.self]
        let loader = RemoteImageLoader(session: URLSession(configuration: config))
        let url = try XCTUnwrap(URL(string: "https://img.test/zero.png"))
        let image = await loader.image(for: url, frame: .zero, scale: 3, fill: true)
        let narrow = await loader.image(for: url, frame: CGSize(width: 0, height: 40), scale: 3, fill: false)
        XCTAssertNil(image)
        XCTAssertNil(narrow)
        XCTAssertNil(loader.cached(for: url, frame: .zero, scale: 3, fill: true))
        XCTAssertEqual(CountingImageProtocol.requests, 0)
    }

    /// The view reads the memory cache in its body, so a cached photo shows without a placeholder frame.
    func testCachedReturnsTheLoadedImageSynchronously() async throws {
        let url = tempURL("cached.png")
        try writePNG(side: 400, to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let loader = RemoteImageLoader()
        let frame = CGSize(width: 50, height: 50)
        XCTAssertNil(loader.cached(for: url, frame: frame, scale: 2, fill: false))
        let loaded = await loader.image(for: url, frame: frame, scale: 2, fill: false)
        XCTAssertNotNil(loaded)
        XCTAssertTrue(loader.cached(for: url, frame: frame, scale: 2, fill: false) === loaded)
        XCTAssertNil(loader.cached(for: url, frame: frame, scale: 2, fill: true))
    }

    func testConcurrentLoadsOfOneKeyShareOneRequest() async throws {
        CountingImageProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CountingImageProtocol.self]
        let loader = RemoteImageLoader(session: URLSession(configuration: config))
        let url = try XCTUnwrap(URL(string: "https://img.test/a.png"))
        let frame = CGSize(width: 40, height: 40)
        async let a = loader.image(for: url, frame: frame, scale: 1, fill: true)
        async let b = loader.image(for: url, frame: frame, scale: 1, fill: true)
        let (first, second) = await (a, b)
        XCTAssertNotNil(first)
        XCTAssertTrue(first === second)
        XCTAssertEqual(CountingImageProtocol.requests, 1)
    }
}

/// Answers every request with a PNG after a short delay and counts the requests.
final class CountingImageProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var count = 0
    static var requests: Int { lock.withLock { count } }
    static func reset() { lock.withLock { count = 0 } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.withLock { Self.count += 1 }
        let data = RemoteImageTests.png(width: 80, height: 80)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}
