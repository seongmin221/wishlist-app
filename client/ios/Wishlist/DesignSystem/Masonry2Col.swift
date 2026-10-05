import SwiftUI

/// 2열 엇갈림(masonry) 배치. 자식을 차례로 더 짧은 열에 쌓아 사진 비율을 살린다(격자에 가두지 않는다).
/// 스크롤은 호출하는 쪽 책임(`ScrollView` 안에 둔다).
struct Masonry2Col: Layout {
    var gap: CGFloat = WishlistTokens.Space.s12

    private func arrange(width: CGFloat, subviews: Subviews) -> (frames: [CGRect], height: CGFloat) {
        let colW = max(0, (width - gap) / 2)
        var heights: [CGFloat] = [0, 0]
        var frames: [CGRect] = []
        for sub in subviews {
            let h = sub.sizeThatFits(ProposedViewSize(width: colW, height: nil)).height
            let col = heights[0] <= heights[1] ? 0 : 1
            frames.append(CGRect(x: CGFloat(col) * (colW + gap), y: heights[col], width: colW, height: h))
            heights[col] += h + gap
        }
        return (frames, max(0, max(heights[0], heights[1]) - gap))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 320
        return CGSize(width: w, height: arrange(width: w, subviews: subviews).height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let a = arrange(width: bounds.width, subviews: subviews)
        for (sub, f) in zip(subviews, a.frames) {
            sub.place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), anchor: .topLeading,
                      proposal: ProposedViewSize(width: f.width, height: f.height))
        }
    }
}
