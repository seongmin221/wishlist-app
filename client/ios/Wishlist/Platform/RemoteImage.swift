import ImageIO
import UIKit

/// 원격(또는 file://) 이미지 로더. URLSession + 디스크 URLCache(50MB), 메모리 NSCache,
/// ImageIO 썸네일 다운샘플링(`maxPixel` = 긴 변 최대 픽셀). 실패하면 nil.
final class RemoteImageLoader {
    static let shared = RemoteImageLoader()

    private let session: URLSession
    private let memory = NSCache<NSString, UIImage>()

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
        let key = "\(url.absoluteString)#\(Int(maxPixel))" as NSString
        if let hit = memory.object(forKey: key) { return hit }
        guard let data = try? await session.data(from: url).0,
              let image = Self.downsample(data, maxPixel: maxPixel) else { return nil }
        memory.setObject(image, forKey: key, cost: Int(image.size.width * image.scale * image.size.height * image.scale * 4))
        return image
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
}
