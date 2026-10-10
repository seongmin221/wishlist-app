import SwiftUI

/// http/https이고 host가 있는 주소만 URL로. 그 외(nil·빈 값·깨진 주소·다른 scheme)는 nil → 자리표시.
func remotePhotoURL(_ raw: String?) -> URL? {
    guard let t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty,
          let url = URL(string: t), let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https", let host = url.host, !host.isEmpty else { return nil }
    return url
}

/// 상품 사진. 주소가 없거나 로딩·실패이면 카드색 면 + 중립 상품 아이콘(D9, 칸 전체). 장식이라 접근성에서 숨긴다.
/// - `fill`: true면 칸을 덮고 넘친 부분을 자른다(scaledToFill), false면 원래 비율로 칸 안에 맞춘다(fit).
/// - `inset`: 사진만 칸 안쪽으로 이만큼 띄운다(상세: 1:1 칸 안 여백 56, 결정 2026-10-04). 자리표시는 칸 전체다.
///
/// 칸 크기가 정해지기 전(0)에는 받지 않고, (주소, 픽셀 크기, 방식)이 바뀔 때만 다시 받는다. 메모리 캐시에 있으면
/// 본문에서 바로 그린다(자리표시가 한 프레임 비치지 않게).
struct ProductPhoto: View {
    let imageUrl: String?
    var fill = true
    var inset: CGFloat = 0

    @Environment(\.wlColors) private var c
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geo in
            let box = CGSize(width: max(0, geo.size.width - 2 * inset), height: max(0, geo.size.height - 2 * inset))
            ProductPhotoImage(url: remotePhotoURL(imageUrl), box: box, scale: scale, fill: fill, placeholder: placeholder)
                .frame(width: geo.size.width, height: geo.size.height)
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
                // addLines starts its own subpath at the first point, so the first corner is in the list.
                p.addLines([CGPoint(x: 6, y: 8), CGPoint(x: 18, y: 8), CGPoint(x: 19, y: 20), CGPoint(x: 5, y: 20)])
                p.closeSubpath()
                p.move(to: CGPoint(x: 9, y: 8)); p.addLine(to: CGPoint(x: 9, y: 7))
                p.addArc(center: CGPoint(x: 12, y: 7), radius: 3, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false) // over the top (SwiftUI counts clockwise in the flipped sense)
                p.addLine(to: CGPoint(x: 15, y: 8))
                ctx.stroke(p, with: .color(c.textSecondary), style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
            }
            .frame(width: 32, height: 32)
        }
    }
}

/// 칸 하나의 사진 읽기: (주소, 픽셀 크기, 방식)이 key다.
private struct ProductPhotoImage<Placeholder: View>: View {
    let url: URL?
    let box: CGSize
    let scale: CGFloat
    let fill: Bool
    let placeholder: Placeholder

    @State private var loaded: (key: LoadKey, image: UIImage)?

    struct LoadKey: Hashable {
        let url: URL?
        let width: Int
        let height: Int
        let fill: Bool
    }

    var body: some View {
        let key = LoadKey(url: url, width: Int((box.width * scale).rounded()), height: Int((box.height * scale).rounded()), fill: fill)
        let image = loaded.flatMap { $0.key == key ? $0.image : nil }
            ?? url.flatMap { RemoteImageLoader.shared.cached(for: $0, frame: box, scale: scale, fill: fill) }
        ZStack {
            if let image {
                if fill {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: box.width, height: box.height).clipped()
                } else {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: box.width, height: box.height)
                }
            } else {
                placeholder
            }
        }
        .task(id: key) {
            guard let url, key.width > 0, key.height > 0, image == nil else { return }
            if let result = await RemoteImageLoader.shared.image(for: url, frame: box, scale: scale, fill: fill), !Task.isCancelled {
                loaded = (key, result)
            }
        }
    }
}
