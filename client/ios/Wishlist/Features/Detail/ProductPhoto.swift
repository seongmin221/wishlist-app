import SwiftUI

/// http/https이고 host가 있는 주소만 URL로. 그 외(nil·빈 값·깨진 주소·다른 scheme)는 nil → 자리표시.
func remotePhotoURL(_ raw: String?) -> URL? {
    guard let t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty,
          let url = URL(string: t), let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https", let host = url.host, !host.isEmpty else { return nil }
    return url
}

/// 상품 사진. 주소가 없거나 로딩·실패이면 카드색 면 + 중립 상품 아이콘(D9). 장식이라 접근성에서 숨긴다.
struct ProductPhoto: View {
    let imageUrl: String?

    @Environment(\.wlColors) private var c
    @Environment(\.displayScale) private var scale
    @State private var loaded: UIImage?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let loaded {
                    Image(uiImage: loaded).resizable().scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height).clipped()
                } else {
                    placeholder
                }
            }
            .task(id: imageUrl) {
                loaded = nil
                guard let url = remotePhotoURL(imageUrl) else { return }
                let px = max(geo.size.width, geo.size.height) * scale
                loaded = await RemoteImageLoader.shared.image(for: url, maxPixel: max(px, 1))
            }
        }
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        ZStack {
            c.card
            Canvas { ctx, sz in
                let k = sz.width / 24
                ctx.scaleBy(x: k, y: k)
                var p = Path()
                p.move(to: CGPoint(x: 6, y: 8)); p.addLines([CGPoint(x: 18, y: 8), CGPoint(x: 19, y: 20), CGPoint(x: 5, y: 20)])
                p.closeSubpath()
                p.move(to: CGPoint(x: 9, y: 8)); p.addLine(to: CGPoint(x: 9, y: 7))
                p.addArc(center: CGPoint(x: 12, y: 7), radius: 3, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
                p.addLine(to: CGPoint(x: 15, y: 8))
                ctx.stroke(p, with: .color(c.textSecondary), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
            }
            .frame(width: 32, height: 32)
        }
    }
}
