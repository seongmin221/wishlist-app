import ImageIO
import UIKit

/// 원격(또는 file://) 이미지 로더. URLSession + 디스크 URLCache(50MB), 메모리 NSCache,
/// ImageIO 썸네일 다운샘플링. 실패하면 nil.
///
/// - `image(for:frame:scale:fill:)`: 그릴 칸 크기(pt)와 채우기 방식으로 필요한 긴 변 픽셀을 원본 크기에서 계산한다
///   (`maxPixel(source:frame:scale:fill:)`; 채우기는 원본 짧은 변이 칸을 덮어야 해서 긴 변이 칸보다 크다).
///   칸 크기가 0이면 아무것도 받지 않는다. 메모리 캐시 key는 (주소, 칸 픽셀 크기, 방식)이라 화면이 본문에서
///   `cached(…)`로 바로 읽을 수 있다(자리표시 깜빡임 없음).
/// - 같은 key를 동시에 요청하면 받기 한 번을 함께 기다린다.
/// - `image(for:maxPixel:)`: 긴 변 최대 픽셀을 직접 준다(Task 9 API).
final class RemoteImageLoader {
    static let shared = RemoteImageLoader()

    private let session: URLSession
    private let memory = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.urlCache = URLCache(memoryCapacity: 0, diskCapacity: 50 * 1024 * 1024)
            config.requestCachePolicy = .returnCacheDataElseLoad
            self.session = URLSession(configuration: config)
        }
        memory.totalCostLimit = 32 * 1024 * 1024
    }

    func image(for url: URL, maxPixel: CGFloat) async -> UIImage? {
        await load(key: "\(url.absoluteString)#\(Int(maxPixel))", url: url) { Self.downsample($0, maxPixel: maxPixel) }
    }

    /// The photo for a `frame` (points) at `scale`, downsampled so that `fill` (cover) or fit never upscales it.
    func image(for url: URL, frame: CGSize, scale: CGFloat, fill: Bool) async -> UIImage? {
        guard let key = Self.frameKey(url, frame: frame, scale: scale, fill: fill) else { return nil }
        return await load(key: key, url: url) { data in
            guard let source = Self.pixelSize(data) else { return nil }
            let px = Self.maxPixel(source: source, frame: frame, scale: scale, fill: fill)
            return px > 0 ? Self.downsample(data, maxPixel: px) : nil
        }
    }

    /// The memory-cached photo for the same arguments, if any (synchronous; safe to call from a view body).
    func cached(for url: URL, frame: CGSize, scale: CGFloat, fill: Bool) -> UIImage? {
        guard let key = Self.frameKey(url, frame: frame, scale: scale, fill: fill) else { return nil }
        return memory.object(forKey: key as NSString)
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
    private static func pixelSize(_ data: Data) -> CGSize? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, opts),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, opts) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? CGFloat, let h = props[kCGImagePropertyPixelHeight] as? CGFloat else { return nil }
        let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
        return orientation >= 5 ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
    }

    private func load(key: String, url: URL, decode: @escaping (Data) -> UIImage?) async -> UIImage? {
        if let hit = memory.object(forKey: key as NSString) { return hit }
        let task: Task<UIImage?, Never> = lock.withLock {
            if let running = inFlight[key] { return running }
            let session = session
            let memory = memory
            let task = Task<UIImage?, Never> {
                guard let data = try? await session.data(from: url).0, let image = decode(data) else { return nil }
                memory.setObject(image, forKey: key as NSString, cost: Int(image.size.width * image.scale * image.size.height * image.scale * 4))
                return image
            }
            inFlight[key] = task
            return task
        }
        let image = await task.value
        lock.withLock { if inFlight[key] == task { inFlight[key] = nil } }
        return image
    }
}
