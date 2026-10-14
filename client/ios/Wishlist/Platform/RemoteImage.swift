import ImageIO
import UIKit

/// 원격(또는 file://) 이미지 로더. URLSession + 디스크 URLCache(50MB), 메모리 NSCache,
/// ImageIO 썸네일 다운샘플링. 실패하면 nil.
///
/// - `image(for:frame:scale:fill:)`: 그릴 칸 크기(pt)와 채우기 방식으로 필요한 긴 변 픽셀을 원본 크기에서 계산한다
///   (`maxPixel(source:frame:scale:fill:)`; 채우기는 원본 짧은 변이 칸을 덮어야 해서 긴 변이 칸보다 크다).
///   칸 크기가 0이면 아무것도 받지 않는다. 메모리 캐시 key는 (주소, 칸 픽셀 크기, 방식)이라 화면이 본문에서
///   `cached(…)`로 바로 읽을 수 있다(자리표시 깜빡임 없음).
/// - `image(for:width:scale:maxPixels:)`(C5 목록 카드): 열 폭 기준(높이 제한 없음)으로 받고 EXIF 회전을 적용한
///   원본 픽셀 크기를 함께 돌려준다. 한 장의 디코드 픽셀은 `maxPixels` 이하로 균일하게 줄인다(비율 유지).
/// - 같은 key를 동시에 요청하면 받기 한 번을 함께 기다린다. 기다리는 쪽이 모두 취소되면 안쪽 작업(받기·디코드)도
///   취소된다. 다른 화면이 같은 key를 기다리면 계속한다.
/// - 로더 전체의 실제 작업(받기 + 디코드)은 동시에 `maxConcurrent`(6)개까지다. 허가를 기다리다 취소된 작업은 시작하지 않는다.
/// - `image(for:maxPixel:)`: 긴 변 최대 픽셀을 직접 준다(Task 9 API).
final class RemoteImageLoader: @unchecked Sendable {
    static let shared = RemoteImageLoader(session: RemoteImageLoader.defaultSession())

    /// A decoded image and the source's displayed pixel size (EXIF orientations 5–8 swapped).
    final class Loaded {
        let image: UIImage
        let source: CGSize?
        init(image: UIImage, source: CGSize?) {
            self.image = image
            self.source = source
        }
    }

    private final class Entry {
        let task: Task<Loaded?, Never>
        var waiters: Set<UUID> = []
        init(task: Task<Loaded?, Never>) { self.task = task }
    }

    private let session: URLSession
    private let memory = NSCache<NSString, Loaded>()
    private let lock = NSLock()
    private var inFlight: [String: Entry] = [:]
    let permits: AsyncPermits

    init(session: URLSession? = nil, maxConcurrent: Int = 6) {
        self.session = session ?? Self.defaultSession()
        permits = AsyncPermits(maxConcurrent)
        memory.totalCostLimit = 32 * 1024 * 1024
    }

    static func defaultSession() -> URLSession {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 0, diskCapacity: 50 * 1024 * 1024)
        config.requestCachePolicy = .returnCacheDataElseLoad
        #if DEBUG
        config.protocolClasses = [SeedImageProtocol.self] + (config.protocolClasses ?? [])
        #endif
        return URLSession(configuration: config)
    }

    func image(for url: URL, maxPixel: CGFloat) async -> UIImage? {
        await load(key: "\(url.absoluteString)#\(Int(maxPixel))", url: url) { data in
            Self.downsample(data, maxPixel: maxPixel).map { Loaded(image: $0, source: Self.pixelSize(data)) }
        }?.image
    }

    /// The photo for a `frame` (points) at `scale`, downsampled so that `fill` (cover) or fit never upscales it.
    func image(for url: URL, frame: CGSize, scale: CGFloat, fill: Bool) async -> UIImage? {
        guard let key = Self.frameKey(url, frame: frame, scale: scale, fill: fill) else { return nil }
        return await load(key: key, url: url) { data in
            guard let source = Self.pixelSize(data) else { return nil }
            let px = Self.maxPixel(source: source, frame: frame, scale: scale, fill: fill)
            guard px > 0, let image = Self.downsample(data, maxPixel: px) else { return nil }
            return Loaded(image: image, source: source)
        }?.image
    }

    /// C5 list card: decoded to the column `width` (points) at `scale` with no height limit, then scaled down
    /// uniformly so width × height ≤ `maxPixels`. Returns the image and the source's displayed pixel size.
    func image(for url: URL, width: CGFloat, scale: CGFloat, maxPixels: CGFloat) async -> (image: UIImage, source: CGSize)? {
        let px = Int((width * scale).rounded())
        guard px > 0, maxPixels > 0 else { return nil }
        let key = "\(url.absoluteString)#w\(px)#\(Int(maxPixels))"
        let loaded = await load(key: key, url: url) { data in
            guard let source = Self.pixelSize(data) else { return nil }
            let target = Self.decodeSize(source: source, width: CGFloat(px), maxPixels: maxPixels)
            guard let image = Self.downsample(data, maxPixel: max(target.width, target.height).rounded(.up)) else { return nil }
            return Loaded(image: image, source: source)
        }
        guard let loaded, let source = loaded.source else { return nil }
        return (loaded.image, source)
    }

    /// The memory-cached photo for the same arguments, if any (synchronous; safe to call from a view body).
    func cached(for url: URL, frame: CGSize, scale: CGFloat, fill: Bool) -> UIImage? {
        guard let key = Self.frameKey(url, frame: frame, scale: scale, fill: fill) else { return nil }
        return memory.object(forKey: key as NSString)?.image
    }

    /// The decode size for a list card: the column width (never above the source's), the height from the
    /// source ratio, then both scaled by the same factor so the pixel count stays within `maxPixels`.
    static func decodeSize(source: CGSize, width: CGFloat, maxPixels: CGFloat) -> CGSize {
        guard source.width > 0, source.height > 0, width > 0 else { return .zero }
        let w = min(width, source.width)
        let h = w * source.height / source.width
        let k = min(1, (maxPixels / (w * h)).squareRoot())
        return CGSize(width: w * k, height: h * k)
    }

    /// The long side (px) the thumbnail needs for `frame` at `scale`: fill covers the frame
    /// (`max` of the two ratios), fit fits inside it (`min`). Never above the source's own long side; 0 for an empty source.
    static func maxPixel(source: CGSize, frame: CGSize, scale: CGFloat, fill: Bool) -> CGFloat {
        guard source.width > 0, source.height > 0 else { return 0 }
        let rx = frame.width * scale / source.width
        let ry = frame.height * scale / source.height
        let ratio = fill ? max(rx, ry) : min(rx, ry)
        let long = max(source.width, source.height)
        return min(long, (long * ratio).rounded(.up))
    }

    static func downsample(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, opts) else { return nil }
        let thumbOpts = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixel),
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static func frameKey(_ url: URL, frame: CGSize, scale: CGFloat, fill: Bool) -> String? {
        let w = Int((frame.width * scale).rounded()), h = Int((frame.height * scale).rounded())
        guard w > 0, h > 0 else { return nil }
        return "\(url.absoluteString)#\(w)x\(h)#\(fill ? "fill" : "fit")"
    }

    /// The displayed pixel size (EXIF orientations 5–8 swap width and height, like the thumbnail's transform).
    static func pixelSize(_ data: Data) -> CGSize? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, opts),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, opts) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? CGFloat, let h = props[kCGImagePropertyPixelHeight] as? CGFloat else { return nil }
        let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
        return orientation >= 5 ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
    }

    /// Joins (or starts) the work for `key`. The work runs in its own Task so several screens share one
    /// download; this caller counts as a waiter until it returns or is cancelled. When the last waiter
    /// leaves before the work ended, the work is cancelled (URLSession stops the transfer, a queued
    /// permit request is dropped).
    private func load(key: String, url: URL, decode: @escaping @Sendable (Data) -> Loaded?) async -> Loaded? {
        if let hit = memory.object(forKey: key as NSString) { return hit }
        let me = UUID()
        let entry: Entry = lock.withLock {
            if let running = inFlight[key] {
                running.waiters.insert(me)
                return running
            }
            let session = session
            let memory = memory
            let permits = permits
            let task = Task<Loaded?, Never> {
                guard await permits.acquire() else { return nil }
                defer { permits.release() }
                guard !Task.isCancelled, let data = try? await session.data(from: url).0, !Task.isCancelled,
                      let loaded = decode(data) else { return nil }
                let image = loaded.image
                memory.setObject(loaded, forKey: key as NSString, cost: Int(image.size.width * image.scale * image.size.height * image.scale * 4))
                return loaded
            }
            let entry = Entry(task: task)
            entry.waiters.insert(me)
            inFlight[key] = entry
            return entry
        }
        let result = await withTaskCancellationHandler {
            await entry.task.value
        } onCancel: {
            leave(key: key, entry: entry, waiter: me)
        }
        leave(key: key, entry: entry, waiter: me)
        return Task.isCancelled ? nil : result
    }

    /// Idempotent per waiter. The last one to leave an unfinished entry cancels its work.
    private func leave(key: String, entry: Entry, waiter: UUID) {
        let cancel: Bool = lock.withLock {
            guard entry.waiters.remove(waiter) != nil else { return false }
            guard entry.waiters.isEmpty else { return false }
            if inFlight[key] === entry { inFlight[key] = nil }
            return true
        }
        if cancel { entry.task.cancel() }
    }
}

/// A counting semaphore for async work. `acquire()` waits for a permit and returns false (holding none)
/// when the waiting task is cancelled; such a waiter is removed from the queue and never starts.
final class AsyncPermits: @unchecked Sendable {
    private let lock = NSLock()
    private var available: Int
    private var queue: [(id: UUID, continuation: CheckedContinuation<Bool, Never>)] = []
    private(set) var maxInUse = 0
    private var inUse = 0

    init(_ count: Int) { available = count }

    func acquire() async -> Bool {
        let id = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                let granted: Bool? = lock.withLock {
                    if Task.isCancelled { return false }
                    if available > 0 {
                        available -= 1
                        inUse += 1
                        maxInUse = max(maxInUse, inUse)
                        return true
                    }
                    queue.append((id, continuation))
                    return nil
                }
                if let granted { continuation.resume(returning: granted) }
            }
        } onCancel: {
            let waiting = lock.withLock { () -> CheckedContinuation<Bool, Never>? in
                guard let index = queue.firstIndex(where: { $0.id == id }) else { return nil }
                return queue.remove(at: index).continuation
            }
            waiting?.resume(returning: false)
        }
    }

    func release() {
        let next = lock.withLock { () -> CheckedContinuation<Bool, Never>? in
            if queue.isEmpty {
                available += 1
                inUse -= 1
                return nil
            }
            return queue.removeFirst().continuation // the permit passes on; inUse unchanged
        }
        next?.resume(returning: true)
    }

    /// Test hook: the highest number of permits held at once.
    var peak: Int { lock.withLock { maxInUse } }
}
