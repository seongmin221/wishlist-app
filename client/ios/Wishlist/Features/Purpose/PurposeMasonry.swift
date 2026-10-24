import Shared
import SwiftUI

/// Two lazy columns from the page plan: laid-out cards in their frozen column, then skeleton cards for
/// the page still waiting (or the first load). The last cards ask for the next page.
struct PurposeMasonry: View {
    let owner: PurposeDetailOwner
    /// C7: cards report their frames for the first-visible anchor.
    var anchors: ListAnchorBox?
    private var state: PurposeDetailState { owner.state }

    @Environment(\.displayScale) private var scale

    var body: some View {
        let plan = owner.plan
        let waiting = state.itemsLoading && state.items.isEmpty ? 6 : plan.waitingCount
        GeometryReader { geo in
            Color.clear.onAppear { owner.setColumn(width: (geo.size.width - WishlistTokens.Space.s12) / 2, scale: scale) }
                .onChange(of: geo.size.width) { _, w in owner.setColumn(width: (w - WishlistTokens.Space.s12) / 2, scale: scale) }
        }
        .frame(height: 0)
        HStack(alignment: .top, spacing: WishlistTokens.Space.s12) {
            ForEach(0..<2, id: \.self) { column in
                LazyVStack(spacing: WishlistTokens.Space.s20) {
                    ForEach(plan.columns[column], id: \.self) { id in
                        if let item = owner.item(id) {
                            PurposeCandidateCard(item: item, ratio: plan.ratio(id) ?? 1, owner: owner)
                                .modifier(PurposeAnchorFrame(id: id, anchors: anchors))
                                .id(id)
                                .onAppear { visible.insert(id) }
                                .onDisappear { visible.remove(id) }
                        }
                    }
                    ForEach(0..<skeletons(waiting, column: column), id: \.self) { i in
                        PurposeSkeletonCard(ratio: [1, 1.25, 1.33][(i + column) % 3])
                    }
                }
                .frame(maxWidth: .infinity, alignment: .top)
            }
        }
        // Re-decided whenever the inputs change, so a request the Presenter ignored (a page in flight) or a
        // first page shorter than the screen still leads to the next page once the list is idle at its end.
        .onChange(of: wantsMore, initial: true) { _, wants in if wants { owner.loadMore() } }
    }

    @State private var visible: Set<String> = []

    private var wantsMore: Bool {
        CategoryLoadMore.wants(hasMore: state.hasMore, busy: state.itemsLoading || state.appending || state.appendFailed,
                               waiting: owner.plan.waitingCount > 0, laidIds: owner.plan.laidIds, visible: visible)
    }

    private func skeletons(_ waiting: Int, column: Int) -> Int {
        let shown = min(waiting, 6) // a page of skeleton never needs more than a screen's worth
        return column == 0 ? (shown + 1) / 2 : shown / 2
    }
}

/// Purpose candidate: frozen photo ratio, review badge, name or analysis status, price, seller and last check.
private struct PurposeCandidateCard: View {
    let item: WishlistItem
    let ratio: CGFloat
    let owner: PurposeDetailOwner

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c
    @State private var image: UIImage?
    /// The URL `image` was loaded from: a refresh that changes or removes the product photo replaces it.
    @State private var imageURL: URL?

    var body: some View {
        Button {
            nav.push(AppDestination.item(item.id).route, sourceKey: "purpose/item/\(item.id)")
        } label: {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
                GeometryReader { geo in
                    ZStack {
                        if let image {
                            Image(uiImage: image).resizable().scaledToFill()
                                .frame(width: geo.size.width, height: geo.size.height).clipped()
                        } else {
                            ProductPhoto(imageUrl: nil)
                        }
                    }
                }
                .aspectRatio(1 / max(ratio, 0.01), contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if CardMetaLineKt.isUnconfirmed(item: item) {
                        WLIconTile(size: 32, radius: WishlistTokens.Radius.xs, color: WishlistTokens.Light.card) {
                            WLIcon(.pending, size: 16, color: WishlistTokens.Light.text)
                        }
                        .padding(WishlistTokens.Space.s8)
                        .accessibilityLabel(String(localized: "category.card.unconfirmed"))
                    }
                }
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                    WLText(PurposeCandidateText.title(name: item.product.name, processing: item.analysis.status == .processing), WLTextStyle.body.resized(14, lineHeight: 14 * 1.35))
                    PriceText(amountText: item.product.price?.canonical, currency: item.product.currency)
                    meta
                }
                .padding(.horizontal, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .task(id: item.product.imageUrl) {
            let url = remotePhotoURL(item.product.imageUrl)
            guard image == nil || url != imageURL else { return }
            guard let url else { image = nil; imageURL = nil; return }
            let loaded = await owner.photo(url)
            guard !Task.isCancelled else { return }
            image = loaded?.image
            imageURL = url
        }
    }

    private var meta: some View {
        TimelineView(.everyMinute) { context in
            let line = CardMetaLineKt.cardMetaLine(item: item, now: DetailClock.instant(context.date), utcOffsetSeconds: DetailClock.utcOffset)
            let merchant = item.product.merchant ?? String(localized: "purpose.unknown.merchant")
            let checked = line.checked.map { DetailLine("category.card.checked", [.text(HomeRowText.time($0))]).resolve() }
            WLText(checked.map { "\(merchant) · \($0)" } ?? merchant, WLTextStyle.label.resized(12), color: c.textSecondary, maxLines: 1)
        }
    }
}

/// A skeleton card: card-color photo box + two bars (no motion; spec §3).
private struct PurposeSkeletonCard: View {
    let ratio: CGFloat

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous).fill(c.card)
                .aspectRatio(1 / ratio, contentMode: .fit)
            RoundedRectangle(cornerRadius: 4).fill(c.card).frame(height: 14).padding(.trailing, 24)
            RoundedRectangle(cornerRadius: 4).fill(c.card).frame(width: 90, height: 18)
        }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "category.loading"))
    }
}

private struct PurposeAnchorFrame: ViewModifier {
    let id: String
    let anchors: ListAnchorBox?

    func body(content: Content) -> some View {
        if let anchors { content.listAnchorFrame(id, into: anchors) } else { content }
    }
}
