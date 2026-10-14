import XCTest
@testable import Wishlist

/// C5: the loader's waiter counting (cancel the inner download when no one waits), the loader-wide
/// six-permit limit counted on real work, the column-width API and its decode cap.
final class RemoteImageLoaderCancellationTests: XCTestCase {
    private func loader(maxConcurrent: Int = 6) -> RemoteImageLoader {
        HoldingImageProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [HoldingImageProtocol.self]
        return RemoteImageLoader(session: URLSession(configuration: config), maxConcurrent: maxConcurrent)
    }

    private func url(_ name: String) -> URL { URL(string: "https://img.test/\(name).png")! }

    private func waitUntil(_ timeout: TimeInterval = 3, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
    }

    func testAllWaitersCancelledCancelsTheDownload() async throws {
        let loader = loader()
        HoldingImageProtocol.delay = 5
        let task = Task { await loader.image(for: url("a"), maxPixel: 50) }
        await waitUntil { HoldingImageProtocol.started == 1 }
        task.cancel()
        let result = await task.value
        XCTAssertNil(result)
        await waitUntil { HoldingImageProtocol.stopped == 1 }
        XCTAssertEqual(HoldingImageProtocol.stopped, 1)
        XCTAssertEqual(HoldingImageProtocol.finished, 0)
    }

    func testOneOfTwoWaitersCancelledKeepsTheDownload() async throws {
        let loader = loader()
        HoldingImageProtocol.delay = 0.4
        let kept = Task { await loader.image(for: url("b"), maxPixel: 50) }
        let dropped = Task { await loader.image(for: url("b"), maxPixel: 50) }
        await waitUntil { HoldingImageProtocol.started == 1 }
        dropped.cancel()
        let image = await kept.value
        XCTAssertNotNil(image)
        XCTAssertEqual(HoldingImageProtocol.started, 1)
        XCTAssertEqual(HoldingImageProtocol.finished, 1)
    }

    func testAtMostSixDownloadsRunAtOnce() async throws {
        let loader = loader()
        HoldingImageProtocol.delay = 0.3
        let images = await withTaskGroup(of: UIImage?.self) { group in
            for i in 0..<14 { group.addTask { await loader.image(for: self.url("c\(i)"), maxPixel: 50) } }
            var out: [UIImage?] = []
            for await image in group { out.append(image) }
            return out
        }
        XCTAssertEqual(images.compactMap { $0 }.count, 14)
        XCTAssertEqual(HoldingImageProtocol.peakActive, 6)
        XCTAssertLessThanOrEqual(loader.permits.peak, 6)
    }

    func testACancelledQueuedLoadNeverStarts() async throws {
        let loader = loader(maxConcurrent: 1)
        HoldingImageProtocol.delay = 0.4
        let first = Task { await loader.image(for: url("d1"), maxPixel: 50) }
        await waitUntil { HoldingImageProtocol.started == 1 }
        let queued = Task { await loader.image(for: url("d2"), maxPixel: 50) }
        try await Task.sleep(for: .milliseconds(50))
        queued.cancel()
        _ = await queued.value
        _ = await first.value
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(HoldingImageProtocol.started, 1)
    }

    func testAKeyRequestedAgainAfterCancelIsANewDownload() async throws {
        let loader = loader()
        HoldingImageProtocol.delay = 5
        let task = Task { await loader.image(for: url("e"), maxPixel: 50) }
        await waitUntil { HoldingImageProtocol.started == 1 }
        task.cancel()
        _ = await task.value
        HoldingImageProtocol.delay = 0.05
        let again = await loader.image(for: url("e"), maxPixel: 50)
        XCTAssertNotNil(again)
        XCTAssertEqual(HoldingImageProtocol.started, 2)
    }

    func testWidthLoadReturnsTheSourceSizeAndCapsTheDecode() async throws {
        let loader = loader()
        HoldingImageProtocol.delay = 0.01
        HoldingImageProtocol.size = CGSize(width: 200, height: 2000) // 1:10
        let result = await loader.image(for: url("tall"), width: 100, scale: 1, maxPixels: 100 * 100 * 3)
        let (image, source) = try XCTUnwrap(result)
        XCTAssertEqual(source, CGSize(width: 200, height: 2000))
        let cg = try XCTUnwrap(image.cgImage)
        XCTAssertLessThanOrEqual(cg.width * cg.height, 100 * 100 * 3 + 200) // thumbnail rounding
        XCTAssertEqual(Double(cg.height) / Double(cg.width), 10, accuracy: 0.5)
    }

    func testDecodeSizeKeepsTheRatio() {
        let normal = RemoteImageLoader.decodeSize(source: CGSize(width: 1000, height: 1250), width: 500, maxPixels: 500 * 500 * 3)
        XCTAssertEqual(normal, CGSize(width: 500, height: 625))
        let tall = RemoteImageLoader.decodeSize(source: CGSize(width: 480, height: 48000), width: 480, maxPixels: 480 * 480 * 3)
        XCTAssertEqual(tall.width * tall.height, 480 * 480 * 3, accuracy: 1)
        XCTAssertEqual(tall.height / tall.width, 100, accuracy: 0.01)
        let small = RemoteImageLoader.decodeSize(source: CGSize(width: 200, height: 200), width: 500, maxPixels: 500 * 500 * 3)
        XCTAssertEqual(small, CGSize(width: 200, height: 200)) // never upscaled
        XCTAssertEqual(RemoteImageLoader.decodeSize(source: .zero, width: 500, maxPixels: 1), .zero)
    }
}

/// Answers each request with a PNG of `size` after `delay` unless stopped first; counts starts, stops,
/// finishes and the peak of concurrently active requests.
final class HoldingImageProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var _started = 0, _stopped = 0, _finished = 0, _active = 0, _peak = 0
    static var delay: TimeInterval {
        get { lock.withLock { _delay } }
        set { lock.withLock { _delay = newValue } }
    }
    static var size: CGSize {
        get { lock.withLock { _size } }
        set { lock.withLock { _size = newValue } }
    }
    private static var _delay: TimeInterval = 0.1
    private static var _size = CGSize(width: 80, height: 80)
    static var started: Int { lock.withLock { _started } }
    static var stopped: Int { lock.withLock { _stopped } }
    static var finished: Int { lock.withLock { _finished } }
    static var peakActive: Int { lock.withLock { _peak } }

    static func reset() {
        lock.withLock { _started = 0; _stopped = 0; _finished = 0; _active = 0; _peak = 0; _delay = 0.1; _size = CGSize(width: 80, height: 80) }
    }

    private var done = false
    private let instanceLock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let (delay, size) = Self.lock.withLock { () -> (TimeInterval, CGSize) in
            Self._started += 1
            Self._active += 1
            Self._peak = max(Self._peak, Self._active)
            return (Self._delay, Self._size)
        }
        let data = RemoteImageTests.png(width: size.width, height: size.height)
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            guard finish() else { return }
            Self.lock.withLock { Self._finished += 1 }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {
        if finish() { Self.lock.withLock { Self._stopped += 1 } }
    }

    /// Ends this request once (finish or stop); true for the first caller. Lowers the active count.
    private func finish() -> Bool {
        let first = instanceLock.withLock { () -> Bool in
            if done { return false }
            done = true
            return true
        }
        if first { Self.lock.withLock { Self._active -= 1 } }
        return first
    }
}
