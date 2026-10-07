import SwiftUI

/// 2열 엇갈림(masonry) 배치. 자식을 차례로 더 짧은 열에 쌓아 사진 비율을 살린다(격자에 가두지 않는다).
/// `gap`은 열 사이, `verticalGap`은 같은 열 카드 사이 간격이다(보드 상품 목록: 12·20, 없으면 `gap`).
/// 스크롤은 호출하는 쪽 책임(`ScrollView` 안에 둔다).
struct Masonry2Col: Layout {
    var gap: CGFloat = WishlistTokens.Space.s12
    var verticalGap: CGFloat?

    private var rowGap: CGFloat { verticalGap ?? gap }

    struct Cache {
        var intrinsicWidth: CGFloat?
        var arrangements: [CGFloat: (frames: [CGRect], height: CGFloat)] = [:]
        var gap: CGFloat?
        var rowGap: CGFloat?
    }

    func makeCache(subviews: Subviews) -> Cache { Cache() }
    func updateCache(_ cache: inout Cache, subviews: Subviews) { cache = Cache() }

    private func arrange(width: CGFloat, subviews: Subviews, cache: inout Cache) -> (frames: [CGRect], height: CGFloat) {
        if cache.gap != gap || cache.rowGap != rowGap { cache = Cache(); cache.gap = gap; cache.rowGap = rowGap }
        if let arrangement = cache.arrangements[width] { return arrangement }
        let colW = max(0, (width - gap) / 2)
        var heights: [CGFloat] = [0, 0]
        var frames: [CGRect] = []
        for sub in subviews {
            let h = sub.sizeThatFits(ProposedViewSize(width: colW, height: nil)).height
            let col = heights[0] <= heights[1] ? 0 : 1
            frames.append(CGRect(x: CGFloat(col) * (colW + gap), y: heights[col], width: colW, height: h))
            heights[col] += h + rowGap
        }
        let arrangement = (frames, max(0, max(heights[0], heights[1]) - rowGap))
        // SwiftUI는 여러 제안 폭을 물을 수 있다. 최근 작은 묶음만 유지한다.
        if cache.arrangements.count >= 4 { cache.arrangements.removeAll(keepingCapacity: true) }
        cache.arrangements[width] = arrangement
        return arrangement
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        if cache.gap != gap || cache.rowGap != rowGap { cache = Cache(); cache.gap = gap; cache.rowGap = rowGap }
        let w: CGFloat
        if let proposed = proposal.width, proposed.isFinite {
            w = max(0, proposed)
        } else {
            if cache.intrinsicWidth == nil {
                cache.intrinsicWidth = 2 * (subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0)
            }
            w = (cache.intrinsicWidth ?? 0) + gap
        }
        return CGSize(width: w, height: arrange(width: w, subviews: subviews, cache: &cache).height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        let a = arrange(width: bounds.width, subviews: subviews, cache: &cache)
        for (sub, f) in zip(subviews, a.frames) {
            sub.place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), anchor: .topLeading,
                      proposal: ProposedViewSize(width: f.width, height: f.height))
        }
    }
}
