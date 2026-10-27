import Shared
import SwiftUI

/// 세부 유형 상품 목록(FCategoryList · ListCustom · ListCustomEmpty, C5). 탭 바 보임.
/// - 위에 붙는 머리: 뒤로 44 · 이름 20/700 + 개수 18/700 보조색 · 상위(직접 만든 것은 " · 직접 만든 카테고리") 13/500,
///   직접 만든 카테고리만 ⋯(편집만, D7).
/// - 2열 엇갈림 카드(D10): 페이지마다 사진 크기를 다 알 때까지(최대 3초) skeleton, 그다음 원래 비율로 한 번에 놓는다. 두 열은
///   각각 지연 생성이라 화면 밖으로 멀리 나간 카드의 사진 요청은 취소된다.
/// - 끝 근처에서 다음 페이지, 실패하면 끝에 "더 불러오지 못했어요 · 다시 시도". 당겨서 새로고침(머리만 실패하면 짧은 안내).
/// - 빈 직접 만든 카테고리는 "아직 상품이 없어요 / 상품 편집에서 …", 없어진 카테고리는 "삭제된 카테고리예요 · 닫기".
/// - 현재 탭을 다시 누르면 맨 위로. 상세에서 돌아오면 위치 유지(스택 칸이 살아 있다).
/// - C7 목록 anchor: 당겨서 새로고침·foreground(이 목록이 보이는 맨 위 화면일 때)는 머리 아래 첫 보이는 카드 id로
///   window를 다시 읽고, Presenter의 `restore`(token마다 한 번, 카드가 놓인 뒤)로 그 카드를 머리 바로 아래에 둔다.
///   위쪽 끝에 닿고 더 새 카드가 있으면 `loadNewer`, 붙은 뒤에도 보던 카드를 그대로 둔다.
struct CategoryListScreen: View {
    let target: CategoryListTarget

    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlRuntime) private var runtime
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.wlColors) private var c
    @Environment(\.displayScale) private var scale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @State private var moreAnchor = WLAnchor()
    @State private var anchors = ListAnchorBox()
    @State private var atTop = true

    private let topID = "category-list-top"

    var body: some View {
        let owner = owners.categoryList(entryID ?? -1)
        let state = owner.state
        ZStack(alignment: .top) {
            c.background.ignoresSafeArea()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                        Section {
                            Color.clear.frame(height: 0).id(topID)
                                .onGeometryChange(for: Bool.self) { [anchors] in $0.frame(in: .global).minY >= anchors.coveredTop - 1 } action: { atTop = $0 }
                            content(owner: owner, state: state)
                        } header: {
                            header(owner: owner, state: state)
                                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { [anchors] frame in
                                    anchors.coveredTop = frame.maxY
                                    anchors.topInset = frame.height
                                }
                        }
                    }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { [anchors] in anchors.viewportHeight = $0 }
                .refreshable { await owner.refreshAndWait(anchor: anchors.firstVisible(top: anchors.coveredTop)) }
                .modifier(ListAnchorWiring(box: anchors, restore: state.restore, laidIds: owner.plan.laidIds,
                                           firstItemId: state.items.first?.id, prepending: state.prepending) { target in
                    switch target {
                    case .top: proxy.scrollTo(topID, anchor: .top)
                    case .item(let id): ListAnchorMath.scroll(proxy, to: id, box: anchors)
                    }
                })
                .ignoresSafeArea(.container, edges: .bottom)
                .onChange(of: nav.scrollToTopRequest) { _, request in
                    guard request == .category, nav.entries(.category).last?.id == entryID else { return }
                    withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WishlistTokens.Motion.headerCollapseScroll)) {
                        proxy.scrollTo(topID, anchor: .top)
                    }
                    nav.consumeScrollToTop(.category)
                }
            }
            GeometryReader { geo in
                c.background.frame(height: geo.safeAreaInsets.top).ignoresSafeArea(edges: .top)
            }
            .allowsHitTesting(false)
        }
        .overlay(alignment: .top) {
            DetailNoticeLine(notice: owner.partialNotice).padding(.top, WLTopBarMetrics.height + WishlistTokens.Space.s32)
        }
        .onAppear { owner.loadOnce(target) }
        .onChange(of: [atTop, state.hasNewer, state.prepending || state.loading], initial: true) { _, key in
            guard anchors.shouldLoadNewer(atTop: key[0], hasNewer: key[1], busy: key[2]) else { return }
            anchors.beginPrepend(top: anchors.coveredTop, firstItemId: state.items.first?.id)
            owner.loadNewer()
        }
        .onChange(of: phase) { _, phase in
            let visible = nav.currentTab == .category && entryID != nil && nav.entries(.category).last?.id == entryID
            if owner.foregroundGate.shouldForeground(phase, visible: visible) {
                owner.foreground(anchor: anchors.firstVisible(top: anchors.coveredTop))
            }
        }
    }

    // MARK: Header

    private func header(owner: CategoryListOwner, state: CategoryListState) -> some View {
        let header = state.header
        let name = header?.name ?? target.name
        let custom = target.isCustom
        return WLTopBar(background: c.background) {
            WLCircleButton(.back, label: String(localized: "category.back")) { nav.pop() }
        } center: {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                HStack(alignment: .firstTextBaseline, spacing: WishlistTokens.Space.s8) {
                    WLText(name, WLTextStyle.title.resized(22), maxLines: 1).wlArrivalFocus()
                    if let count = header?.count {
                        WLText(String(count.intValue), WLTextStyle.price.resized(20), color: c.textSecondary, maxLines: 1)
                    }
                }
                WLText(CategoryText.listSubtitle(parentName: header?.parentName ?? target.parentName, custom: custom).resolvedText(),
                       WLTextStyle.label.resized(13), color: c.textSecondary, maxLines: 1)
            }
        } trailing: {
            if custom && !state.gone {
                Button { openMenu(owner: owner, state: state) } label: {
                    WLIcon(.more)
                        .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                        .background(c.card, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .wlAnchor(moreAnchor)
                .accessibilityLabel(String(localized: "category.list.more"))
            }
        }
    }

    private func openMenu(owner: CategoryListOwner, state: CategoryListState) {
        guard let overlay else { return }
        overlay.showMenu(anchor: moreAnchor.frame, items: [
            WLMenuItem(text: String(localized: "category.menu.edit"), icon: AnyView(WLIcon(.pen, size: 18))) {
                openEdit(owner: owner)
            },
        ])
    }

    private func openEdit(owner: CategoryListOwner) {
        guard let overlay else { return }
        let parentName = owner.state.header?.parentName ?? target.parentName
        let categoryId = target.categoryId
        let homeEntry = nav.entries(.category).first?.id
        #if DEBUG
        let beforeSubmit = DebugLaunchHooks.editOnSave(runtime: runtime, customId: categoryId)
        #else
        let beforeSubmit: (() async -> Void)? = nil
        #endif
        showCategoryFormSheet(overlay: overlay, owners: owners, start: { form in
            form.startEdit(customId: categoryId, parentName: parentName)
        }, beforeSubmit: beforeSubmit, onResult: { result in
            if let saved = result as? FormResultSaved {
                owner.applyEdited(saved.detail)
                if let homeEntry { owners.categoryHome(homeEntry).refresh() }
            } else if result is FormResultGone {
                owner.refreshHeader() // reads CATEGORY_NOT_FOUND: the list shows "삭제된 카테고리예요"
            }
        })
    }

    // MARK: Body

    @ViewBuilder
    private func content(owner: CategoryListOwner, state: CategoryListState) -> some View {
        if state.gone {
            DetailStatusBlock(message: String(localized: "category.gone"), buttonText: String(localized: "category.close")) { nav.pop() }
                .padding(.top, 160)
        } else if state.items.isEmpty, let error = state.itemsError {
            DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "detail.retry")) {
                owner.retry()
            }
            .padding(.top, 160)
        } else if state.items.isEmpty && !state.loading && state.totalCount != nil {
            EmptyState(title: String(localized: "category.empty.title"),
                       description: target.isCustom ? String(localized: "category.list.empty.body") : "") {
                WLIcon(.apps, size: 24)
            }
            .padding(.top, 200)
        } else {
            CategoryMasonry(owner: owner, state: state, anchors: anchors)
                .padding(.horizontal, WishlistTokens.Space.s16)
                .padding(.top, WishlistTokens.Space.s8)
            if state.appendFailed {
                HStack(spacing: WishlistTokens.Space.s8) {
                    WLText(String(localized: "category.load.more.failed"), WLTextStyle.label.resized(13), color: c.textSecondary)
                    Button { owner.retry() } label: {
                        WLText(String(localized: "detail.retry"), WLTextStyle.bodyBold.resized(13), color: c.text).underline()
                    }
                    .buttonStyle(.plain)
                }
                .padding(.vertical, WishlistTokens.Space.s16)
            }
            Color.clear.frame(height: 140)
        }
    }
}

/// Two lazy columns from the page plan: laid-out cards in their frozen column, then skeleton cards for
/// the page still waiting (or the first load). The last cards ask for the next page.
private struct CategoryMasonry: View {
    let owner: CategoryListOwner
    let state: CategoryListState
    let anchors: ListAnchorBox

    @Environment(\.displayScale) private var scale

    var body: some View {
        let plan = owner.plan
        let waiting = state.loading && state.items.isEmpty ? 6 : plan.waitingCount
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
                            CategoryCard(item: item, ratio: plan.ratio(id) ?? 1, owner: owner)
                                .listAnchorFrame(id, into: anchors)
                                .id(id)
                                .onAppear { visible.insert(id) }
                                .onDisappear { visible.remove(id) }
                        }
                    }
                    ForEach(0..<skeletons(waiting, column: column), id: \.self) { i in
                        CategorySkeletonCard(ratio: [1, 1.25, 1.33][(i + column) % 3])
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
        CategoryLoadMore.wants(hasMore: state.hasMore, busy: state.loading || state.appending || state.appendFailed,
                               waiting: owner.plan.waitingCount > 0, laidIds: owner.plan.laidIds, visible: visible)
    }

    private func skeletons(_ waiting: Int, column: Int) -> Int {
        let shown = min(waiting, 6) // a page of skeleton never needs more than a screen's worth
        return column == 0 ? (shown + 1) / 2 : shown / 2
    }
}

/// When the list asks for its next page (pure): more exists, nothing is in flight or failed (a failure
/// waits for the user's retry), no page is still waiting for its layout, and one of the last eight
/// laid-out cards is on screen (a short first page has all of them on screen).
enum CategoryLoadMore {
    static let tail = 8

    static func wants(hasMore: Bool, busy: Bool, waiting: Bool, laidIds: [String], visible: Set<String>) -> Bool {
        guard hasMore, !busy, !waiting, !laidIds.isEmpty else { return false }
        return laidIds.suffix(tail).contains { visible.contains($0) }
    }
}

/// A card (board FCategoryList): photo r20 at its frozen ratio (a late photo fills it), ✳ for pending and
/// deferred reviews (D13), name 14/1.35, price 18/700 (hidden without one), purpose dot + "purpose · N일 전 확인" 12/500.
private struct CategoryCard: View {
    let item: WishlistItem
    let ratio: CGFloat
    let owner: CategoryListOwner

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlColors) private var c
    @State private var image: UIImage?
    /// The URL `image` was loaded from: a refresh that changes or removes the product photo replaces it.
    @State private var imageURL: URL?

    var body: some View {
        let sourceKey = "category/item/\(item.id)"
        Button {
            // Card photo → detail photo (C5 PR B): the image the card draws travels with the push.
            PhotoTransitionDebug.onPhotoTransitionStart()
            if nav.push(AppDestination.item(item.id, viaPhoto: true).route, sourceKey: sourceKey),
               let image, let entry = nav.entries(nav.currentTab).last {
                motion.handoff.hold(entry.id, image)
            }
        } label: {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
                // The flight layer rebuilds this content outside the card: it draws the captured `image` value.
                let shown = image
                WLSharedPhotoSource(key: sourceKey) {
                    GeometryReader { geo in
                        ZStack {
                            if let shown {
                                Image(uiImage: shown).resizable().scaledToFill()
                                    .frame(width: geo.size.width, height: geo.size.height).clipped()
                            } else {
                                ProductPhoto(imageUrl: nil)
                            }
                        }
                    }
                }
                .aspectRatio(1 / max(ratio, 0.01), contentMode: .fit)
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
                    WLText(item.product.name ?? "", WLTextStyle.body.resized(14, lineHeight: 14 * 1.35))
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
        CardMinuteClock { now in
            let line = CardMetaLineKt.cardMetaLine(item: item, now: DetailClock.instant(now), utcOffsetSeconds: DetailClock.utcOffset)
            let dot = CategoryText.cardDot(line)
            HStack(spacing: 6) {
                if dot.shown { PurposeColorDot(colorKey: dot.colorKey) }
                WLText(CategoryText.cardMeta(line), WLTextStyle.label.resized(12), color: c.textSecondary, maxLines: 1)
            }
        }
    }
}

/// Redraws its content once a minute ("2일 전 확인" stays current while the list is open).
private struct CardMinuteClock<Content: View>: View {
    @ViewBuilder let content: (Date) -> Content

    var body: some View {
        TimelineView(.everyMinute) { context in content(context.date) }
    }
}

/// A skeleton card: card-color photo box + two bars (no motion; spec §3).
private struct CategorySkeletonCard: View {
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
