#if DEBUG
import UIKit

/// DEBUG only: answers the Fake seed photo host `seed.wishlist.invalid` so the C5 list can be checked
/// with real photos (`DebugControls.seedLongCategory`): `/tall/…` is a 1:4 photo, `/slow/…` a 1:1 photo
/// after 5 s (the page skeleton's 3 s timeout), anything else fails like the real invalid host.
final class SeedImageProtocol: URLProtocol {
    static let host = "seed.wishlist.invalid"
    private var cancelled = false

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let path = request.url?.path ?? ""
        let size: CGSize?
        let delay: TimeInterval
        if path.hasPrefix("/tall/") { size = CGSize(width: 300, height: 1200); delay = 0.2 }
        else if path.hasPrefix("/slow/") { size = CGSize(width: 600, height: 600); delay = 5 }
        else { size = nil; delay = 0 }
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            guard !cancelled, let url = request.url else { return }
            guard let size else {
                client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
                return
            }
            let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Self.png(size))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() { cancelled = true }

    /// A vertical two-tone photo so the aspect ratio is visible on screen.
    static func png(_ size: CGSize) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor(red: 0.48, green: 0.42, blue: 0.36, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            UIColor(red: 0.91, green: 0.87, blue: 0.82, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: size.height / 2, width: size.width, height: size.height / 2))
        }.pngData()!
    }
}
#endif
