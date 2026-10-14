import Shared
import SwiftUI

/// The pure part of the vertical pager: where a drag ends up (Android has the same rules).
enum CategoryPagerMath {
    /// Distance (fraction of the page height) or speed that moves to the neighbouring page.
    static let turnFraction: CGFloat = 0.2
    static let turnVelocity: CGFloat = 600
    /// How far the first page must be pulled down to refresh.
    static let refreshPull: CGFloat = 70
    /// Past the first or last page the pager follows the finger at this rate.
    static let edgeResistance: CGFloat = 0.4

    /// Splits a drag step `delta` (positive = down) between the page's own scroll (`inner`, 0…`innerMax`,
    /// growing as content moves up) and the pager offset: the page moves first, the rest moves the pager.
    static func step(delta: CGFloat, inner: CGFloat, innerMax: CGFloat, pager: CGFloat) -> (inner: CGFloat, pager: CGFloat) {
        var pager = pager, d = delta
        // A displaced pager first goes back toward rest; only then does the page scroll again.
        if pager > 0, d < 0 { let m = max(d, -pager); pager += m; d -= m }
        else if pager < 0, d > 0 { let m = min(d, -pager); pager += m; d -= m }
        if pager != 0 { return (inner, pager + d) }
        let wanted = inner - d // content follows the finger: dragging down scrolls back up
        let clamped = min(max(0, wanted), innerMax)
        return (clamped, pager - (wanted - clamped))
    }

    /// The page after a drag that ended with the pager `offset` and `velocity` (pt/s, positive = down).
    static func target(page: Int, count: Int, offset: CGFloat, velocity: CGFloat, height: CGFloat) -> Int {
        guard count > 0, height > 0 else { return 0 }
        if offset < -height * turnFraction || (offset < 0 && velocity < -turnVelocity) { return min(count - 1, page + 1) }
        if offset > height * turnFraction || (offset > 0 && velocity > turnVelocity) { return max(0, page - 1) }
        return page
    }
}

/// Rail 124 + vertical pager (decisions 2026-10-07). See `CategoryHomeScreen`.
struct CategoryPager: View {
    let owner: CategoryHomeOwner
    let state: CategoryHomeState
    let onAdd: (String) -> Void

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var navigator
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var page = 0
    @State private var pinned: Int?
    @State private var pager: CGFloat = 0
    @State private var inner: [Int: CGFloat] = [:]
    @State private var contentHeights: [Int: CGFloat] = [:]
    @State private var lastTranslation: CGFloat = 0
    @State private var dragging = false
    @State private var refreshing = false

    private var groups: [CategoryGroup] { state.groups }
    private var selected: Int { pinned ?? page }
    private var snap: Animation? { reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll) }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            rail
            GeometryReader { geo in
                let height = geo.size.height
                ZStack(alignment: .top) {
                    ForEach(Array(groups.enumerated()), id: \.element.id) { index, group in
                        CategoryPage(group: group, onAdd: { onAdd(group.id) })
                            .background(GeometryReader { Color.clear.preference(key: PageHeightKey.self, value: [index: $0.size.height]) })
                            .offset(y: CGFloat(index - page) * height + pager - (inner[index] ?? 0))
                            .accessibilityHidden(index != page)
                    }
                    if refreshing || pager > 0 && page == 0 {
                        ProgressView()
                            .padding(.top, 12)
                            .opacity(refreshing ? 1 : min(1, pager / CategoryPagerMath.refreshPull))
                    }
                }
                .frame(width: geo.size.width, height: height, alignment: .top)
                .clipped()
                .contentShape(Rectangle())
                .gesture(drag(height: height))
                .onPreferenceChange(PageHeightKey.self) { contentHeights.merge($0) { $1 } }
            }
        }
        .onAppear { syncFromState(animated: false) }
        .onChange(of: state.currentGroupId) { _, _ in if !dragging && pinned == nil { syncFromState(animated: false) } }
        .onChange(of: groups.map(\.id)) { _, _ in
            page = min(page, max(0, groups.count - 1))
            syncFromState(animated: false)
        }
        .onChange(of: navigator.scrollToTopRequest) { _, request in
            guard request == .category, navigator.entries(.category).last?.id == entryID else { return }
            go(to: 0)
            navigator.consumeScrollToTop(.category)
        }
    }

    // MARK: Rail

    private var rail: some View {
        ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(groups.enumerated()), id: \.element.id) { index, group in
                        railRow(group, on: index == selected) { go(to: index) }.id(group.id)
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 120)
            }
            .frame(width: 124)
            .onChange(of: selected) { _, index in
                guard groups.indices.contains(index) else { return }
                withAnimation(snap) { proxy.scrollTo(groups[index].id, anchor: .center) }
            }
        }
    }

    private func railRow(_ group: CategoryGroup, on: Bool, action: @escaping () -> Void) -> some View {
        let style = (on ? WLTextStyle.bodyBold : WLTextStyle.body).resized(15, lineHeight: 15 * 1.3)
        return Button(action: action) {
            HStack(spacing: 0) {
                Rectangle().fill(on ? c.text : Color.clear).frame(width: 3)
                WLText(CategoryText.railLabel(group.name), style, color: on ? c.text : c.textSecondary)
                    .padding(.leading, 17)
                    .padding(.trailing, 10)
                    .padding(.vertical, WishlistTokens.Space.s8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(group.name)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    // MARK: Paging

    private func drag(height: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard !groups.isEmpty, height > 0 else { return }
                if !dragging { dragging = true; lastTranslation = 0; pinned = nil }
                let delta = value.translation.height - lastTranslation
                lastTranslation = value.translation.height
                let innerMax = max(0, (contentHeights[page] ?? 0) - height)
                var (nextInner, nextPager) = CategoryPagerMath.step(delta: delta, inner: inner[page] ?? 0, innerMax: innerMax, pager: pager)
                let atEdge = (page == 0 && nextPager > 0) || (page == groups.count - 1 && nextPager < 0)
                if atEdge { nextPager = pager + (nextPager - pager) * CategoryPagerMath.edgeResistance }
                inner[page] = nextInner
                pager = nextPager
            }
            .onEnded { value in
                dragging = false
                guard height > 0 else { return }
                if page == 0, pager >= CategoryPagerMath.refreshPull, !refreshing {
                    refreshing = true
                    withAnimation(snap) { pager = 0 }
                    Task {
                        await owner.refreshAndWait()
                        refreshing = false
                    }
                    return
                }
                let target = CategoryPagerMath.target(page: page, count: groups.count, offset: pager, velocity: value.velocity.height, height: height)
                // Each page's offset animates from where the finger left it to its resting place.
                withAnimation(snap) {
                    page = target
                    pager = 0
                }
                report()
            }
    }

    /// Rail tap or tab re-tap: the target stays selected while the pager moves (no flicker through pages between).
    private func go(to index: Int) {
        guard groups.indices.contains(index) else { return }
        pinned = index
        withAnimation(snap) {
            page = index
            pager = 0
            inner[index] = 0
        } completion: {
            if pinned == index { pinned = nil }
        }
        report()
    }

    private func report() {
        guard groups.indices.contains(page) else { return }
        owner.showGroup(groups[page].id)
    }

    private func syncFromState(animated: Bool) {
        guard let id = state.currentGroupId, let index = groups.firstIndex(where: { $0.id == id }), index != page else { return }
        withAnimation(animated ? snap : nil) { page = index; pager = 0 }
    }
}

private struct PageHeightKey: PreferenceKey {
    static let defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) { value.merge(nextValue()) { $1 } }
}

/// One group's page: label (13/500, min 44) + 8 + chips (name + count) and the dashed `+ 추가`.
/// Padding 8 / 16 / 120 / 8 (board). The page may be taller than the pager (large text); the pager scrolls it first.
private struct CategoryPage: View {
    let group: CategoryGroup
    let onAdd: () -> Void

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(group.name, WLTextStyle.label.resized(13), color: c.textSecondary, maxLines: 1)
                .frame(minHeight: 44, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            WLFlowLayout {
                ForEach(group.categories, id: \.id) { entry in
                    WLChip(text: entry.name, count: Int(entry.itemCount)) {
                        let target = CategoryListTarget(entry: entry, parentName: group.name)
                        nav.push(AppDestination.categoryList(target).route, sourceKey: "category/\(entry.id)")
                    }
                }
                WLAddChip(text: String(localized: "category.add.chip"), action: onAdd)
            }
        }
        .padding(.top, 8)
        .padding(.leading, WishlistTokens.Space.s8)
        .padding(.trailing, WishlistTokens.Space.s16)
        .padding(.bottom, 120)
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }
}
