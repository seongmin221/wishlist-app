import CoreGraphics
import Foundation

/// C5 D10 page-level photo layout (pure; the list owner drives it, Android `PhotoPagePlanner` has the same rules).
///
/// - Each ITEM-02 page waits (skeleton cards) until every photo's size is known (or failed, or the card has
///   no photo) or `timeout` (3 s) passed since the page arrived; then the whole page is laid out at once.
/// - A laid-out card keeps its height: the ratio is the source photo's own (EXIF applied, no clamp); no
///   photo, a failure or a size still unknown at layout time is 1:1, and a photo that arrives later fills that 1:1 box.
/// - Pages lay out in order: a later page never shows before an earlier one.
/// - Column assignment is shortest column first by the frozen photo heights plus a fixed text estimate,
///   so it never changes once laid out.
/// - The state holds sizes and ratios only, never images.
struct PhotoPagePlan: Equatable {
    static let timeout: TimeInterval = 3
    /// Photo pixels decoded for one card at most (C5: column width² × 3).
    static func maxPixels(columnPixels: CGFloat) -> CGFloat { columnPixels * columnPixels * 3 }

    struct Item: Equatable {
        let id: String
        let hasPhoto: Bool
    }

    private struct Page: Equatable {
        let items: [Item]
        let arrived: Date
        var laid = false
    }

    private var pages: [Page] = []
    private var sizes: [String: CGSize] = [:]
    private var failed: Set<String> = []
    private(set) var ratios: [String: CGFloat] = [:]
    private(set) var columns: [[String]] = [[], []]
    private var heights: [CGFloat] = [0, 0]

    /// Item ids already handed to the plan, in order.
    var knownIds: [String] { pages.flatMap { $0.items.map(\.id) } }

    /// Ids of the laid-out pages (shown as cards), in order.
    var laidIds: [String] { pages.prefix { $0.laid }.flatMap { $0.items.map(\.id) } }

    /// Items waiting for their page (shown as skeleton cards).
    var waitingCount: Int { pages.drop { $0.laid }.reduce(0) { $0 + $1.items.count } }

    /// Photos whose size is still needed (to prefetch); empty once laid out.
    var sizesNeeded: [String] {
        pages.filter { !$0.laid }.flatMap { $0.items }.filter { $0.hasPhoto && sizes[$0.id] == nil && !failed.contains($0.id) }.map(\.id)
    }

    /// Follows the Presenter's item list: a list that only grew adds its tail as a new page; any other
    /// change (refresh, another category) starts over with the whole list as the first page.
    mutating func sync(_ items: [Item], now: Date) {
        let known = knownIds
        if items.count >= known.count, Array(items.prefix(known.count).map(\.id)) == known {
            let tail = Array(items.dropFirst(known.count))
            if !tail.isEmpty { pages.append(Page(items: tail, arrived: now)) }
        } else {
            self = PhotoPagePlan()
            if !items.isEmpty { pages.append(Page(items: items, arrived: now)) }
        }
        layOut(now: now)
    }

    /// A photo's displayed pixel size (nil: it failed). Ignored once its page is laid out (heights never change).
    mutating func report(_ id: String, size: CGSize?, now: Date) {
        if let size, size.width > 0, size.height > 0 { sizes[id] = size } else { failed.insert(id) }
        layOut(now: now)
    }

    /// Lays out pages whose timeout passed.
    mutating func tick(now: Date) { layOut(now: now) }

    /// When the first waiting page times out (nil: nothing is waiting).
    var nextDeadline: Date? { pages.first { !$0.laid }.map { $0.arrived.addingTimeInterval(Self.timeout) } }

    /// The frozen height / width of a laid-out card's photo; nil while its page waits.
    func ratio(_ id: String) -> CGFloat? { ratios[id] }

    private func ready(_ page: Page, now: Date) -> Bool {
        if now >= page.arrived.addingTimeInterval(Self.timeout) { return true }
        return page.items.allSatisfy { !$0.hasPhoto || sizes[$0.id] != nil || failed.contains($0.id) }
    }

    private mutating func layOut(now: Date) {
        for index in pages.indices {
            if pages[index].laid { continue }
            guard ready(pages[index], now: now) else { return }
            for item in pages[index].items {
                let ratio = sizes[item.id].map { $0.height / $0.width } ?? 1
                ratios[item.id] = ratio
                let column = heights[0] <= heights[1] ? 0 : 1
                columns[column].append(item.id)
                heights[column] += ratio + Self.textEstimate
            }
            pages[index].laid = true
        }
    }

    /// The text block under a photo (name, price, meta) in photo-width units, for column balance only.
    private static let textEstimate: CGFloat = 0.55
}
