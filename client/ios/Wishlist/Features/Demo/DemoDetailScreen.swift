import SwiftUI

#if DEBUG

/// 데모 상세(debug 빌드에서만 진입). 타입이 있는 목적지로 상품 상세·세부 유형 목록·목적 상세를 고른다.
/// 사진 없는 화면(세부 유형 목록·목적 상세)은 가로 밀기로 열린다. 밀리는 동안 아래 화면이 비치지 않게 자기 바탕을 칠한다
/// (목적 상세는 상태 바 뒤까지 목적 색).
struct DemoDetailScreen: View {
    let destination: DemoDestination

    var body: some View {
        switch destination {
        case .product(let id):
            if let p = DemoContent.product(id) { DemoProductDetail(product: p) }
        case .categoryList(let top, let chip):
            DemoCategoryList(top: top, chip: DemoContent.chip(top: top, name: chip) ?? DemoChip(name: chip, count: 0))
        case .purpose(let id):
            if let p = DemoContent.purpose(id) { DemoPurposeDetail(purpose: p) }
        }
    }
}

/// 떠 있는 위쪽 바: 뒤로 ↔ ⋯ 원형 버튼(44, 카드색, `WLTopBar` 자리). 화면의 뒤로 버튼도 끌어서 뒤로와 같은 pop 전환을 쓴다.
private struct DemoTopBar: View {
    var body: some View {
        WLTopBar {
            DemoBarBack()
        } center: {
            EmptyView()
        } trailing: {
            DemoMoreButton()
        }
    }
}

private struct DemoMoreButton: View {
    @Environment(\.overlayHostState) private var overlay
    @State private var anchor = WLAnchor()

    var body: some View {
        DemoCircleButton(description: "더 보기", action: {
            if let overlay { overlay.showMenu(anchor: anchor.frame, items: demoMenuItems(overlay)) }
        }) { MoreDots() }
        .wlAnchor(anchor)
    }
}

/// 안전 영역 위 높이를 읽어 내용에 넘긴다(상태 바 뒤까지 칠하는 화면의 보드 위치 환산용).
private struct DemoSafeTop<Content: View>: View {
    @ViewBuilder let content: (_ safeTop: CGFloat, _ height: CGFloat) -> Content

    var body: some View {
        GeometryReader { proxy in
            content(proxy.safeAreaInsets.top, proxy.size.height + proxy.safeAreaInsets.top + proxy.safeAreaInsets.bottom)
        }
    }
}

// MARK: - 상품 상세 (FProductDetailL)

/// 상품 상세: 뒤로·⋯ 고정(스크롤과 무관), 흰 정사각 사진 면(모서리 20, 안쪽 56) 안에 사진 aspect-fit, 브랜드·제품명·가격·안내,
/// 정보 카드, 저장 날짜, 하단 고정 "원본 보기". 탭 바 없음.
///
/// 사진 공유 요소의 상세 자리(`WLSharedPhotoTarget`)는 fit된 사진 사각형만 감싼다. 원래 사진과 비율이 같아 전환 층 사진이
/// 마지막 프레임에 크기·모양을 바꾸지 않고 상세 사진으로 넘어간다(정사각 면 전체를 상세 자리로 잡으면 끝에서 튄다).
private struct DemoProductDetail: View {
    let product: DemoProduct

    @Environment(\.wlColors) private var c

    /// 사진 면은 버튼 아래 12(= safeTop + 68)부터(명세 7), 안쪽 56, 아래 바 padding 12/20/36.
    private enum Layout {
        static let photoTop: CGFloat = WLTopBarMetrics.buttonTop(safeTop: 0) + WLTopBarMetrics.button + WishlistTokens.Space.s12
        static let photoInset: CGFloat = 56
        static let barTop: CGFloat = 12
        static let barBottom: CGFloat = 36
        static let buttonHeight: CGFloat = 56
    }

    var body: some View {
        ZStack(alignment: .top) {
            c.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    photo
                    info.padding(.top, WishlistTokens.Space.s20)
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.top, Layout.photoTop)
                .padding(.bottom, 140)
            }
            .ignoresSafeArea(.container, edges: .bottom)
            DemoTopBar()
            bottomBar
        }
    }

    private var photo: some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)
        return Color.clear
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .overlay {
                WLSharedPhotoTarget {
                    DemoPhoto(art: product.art).aspectRatio(1 / product.photoRatio, contentMode: .fit)
                }
                .padding(Layout.photoInset)
            }
            .background(c.card, in: shape)
            .accessibilityHidden(true)
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s20) {
            VStack(alignment: .leading, spacing: 0) {
                WLText(product.brand, .bodyBold, maxLines: 1)
                WLText(product.name, .title, maxLines: 1).wlArrivalFocus()
                    .padding(.top, WishlistTokens.Space.s8)
                PriceText(amount: product.price, currency: product.currency, style: WLTextStyle.price.resized(24))
                    .padding(.top, 10)
                WLText("2일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.", WLTextStyle.body.resized(13, lineHeight: 13 * 1.5),
                       color: c.textSecondary)
                    .padding(.top, WishlistTokens.Space.s4)
            }
            WLCard {
                VStack(spacing: 0) {
                    DemoInfoRow(label: "카테고리") { WLText(product.category, Self.valueStyle, maxLines: 1) }
                    DemoInfoRow(label: "목적") {
                        if let purpose = product.purpose {
                            HStack(spacing: WishlistTokens.Space.s8) {
                                PurposeDot(color: purpose.color)
                                WLText(purpose.name, Self.valueStyle, maxLines: 1)
                            }
                        } else {
                            WLText("목적 미지정", WLTextStyle.body.resized(15), color: c.textSecondary, maxLines: 1)
                        }
                    }
                }
                .padding(.vertical, WishlistTokens.Space.s4)
            }
            WLText("9월 28일 저장", .demoCaption, color: c.textSecondary, maxLines: 1)
        }
    }

    /// 정보 카드 값 15/700.
    static let valueStyle = WLTextStyle.bodyBold.resized(15)

    /// 하단 고정 바(바탕색): "원본 보기" 56 알약 먹색 + 외부 열기 아이콘. 보드처럼 화면 아래 끝에서 36 위에 둔다.
    private var bottomBar: some View {
        Button {} label: {
            HStack(spacing: WishlistTokens.Space.s8) {
                WLText("원본 보기", .button, color: c.onInverse, maxLines: 1)
                DemoLineIcon(icon: .external, size: 18, color: c.onInverse)
            }
            .frame(maxWidth: .infinity, minHeight: Layout.buttonHeight)
            .background(c.text, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, WishlistTokens.Space.screenMargin)
        .padding(.top, Layout.barTop)
        .padding(.bottom, Layout.barBottom)
        .background(c.background)
        .frame(maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea(.container, edges: .bottom)
    }
}

/// 정보 카드 줄: min-height 56, 좌우 16, 라벨 폭 60 14 보조색, 값 오른쪽 정렬.
private struct DemoInfoRow<Value: View>: View {
    let label: String
    @ViewBuilder let value: () -> Value

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s12) {
            WLText(label, .body, color: c.textSecondary, maxLines: 1).frame(width: 60, alignment: .leading)
            value().frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, WishlistTokens.Space.s16)
        .frame(minHeight: 56)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 세부 유형 목록 (FCategoryListL)

/// 칩 → 세부 유형 상품 목록: 스크롤 시 위에 붙는 머리(뒤로 + 제목·개수 + 상위 이름) + 2열 엇갈림 사진 카드. 탭 바 보임.
/// 사진 카드 → 상품 상세는 사진 공유 요소 이동(스택 깊이 2).
private struct DemoCategoryList: View {
    let top: String
    let chip: DemoChip

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlEntryID) private var entryID

    var body: some View {
        ZStack(alignment: .top) {
            // 밀기 동안 아래 화면이 비치지 않게 자기 바탕을 칠한다.
            c.background.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    Section {
                        Masonry2Col(gap: WishlistTokens.Space.s12, verticalGap: WishlistTokens.Space.s20) {
                            ForEach(DemoContent.headphones, id: \.id) { p in
                                DemoProductCard(product: p, sourceKey: "entry\(entryID ?? -1)/product/\(p.id)")
                            }
                        }
                        .padding(.horizontal, WishlistTokens.Space.s16)
                        .padding(.top, WishlistTokens.Space.s8)
                        .padding(.bottom, 140)
                    } header: {
                        header
                    }
                }
            }
            .ignoresSafeArea(.container, edges: .bottom)
            // 붙은 머리 위 상태 바 자리도 바탕색으로 가린다(목록이 상태 바 뒤로 비치지 않게). 스크롤 안의 붙은 머리는
            // 안전 영역 밖을 칠하지 못하므로 실제 안전 영역 높이만큼 화면 맨 위에 따로 칠한다.
            DemoSafeTop { safeTop, _ in
                c.background
                    .frame(height: safeTop)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(edges: .top)
            }
            .allowsHitTesting(false)
        }
    }

    /// 위에 붙는 머리 = `WLTopBar`(바탕색, 상태 바 뒤까지 칠함): 뒤로 44 + 12 + 바 세로 가운데의 제목 덩어리
    /// [제목 20/700 · 8 · 숫자 18/700 보조색(기준선) / 4 / 상위 이름 13/500]. ⋯ 메뉴는 없다(직접 만든 카테고리만 ⋯).
    private var header: some View {
        WLTopBar(background: c.background) {
            DemoCircleButton(description: "뒤로", action: { nav.pop() }) { BackChevron() }
        } center: {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                HStack(alignment: .firstTextBaseline, spacing: WishlistTokens.Space.s8) {
                    WLText(chip.name, WLTextStyle.title.resized(20), maxLines: 1).wlArrivalFocus()
                    WLText(String(chip.count), WLTextStyle.price.resized(18), color: c.textSecondary, maxLines: 1)
                }
                WLText(top, .demoCaption, color: c.textSecondary, maxLines: 1)
            }
        } trailing: {
            EmptyView()
        }
    }
}

// MARK: - 목적 상세 (FPurposeDetailL, 명세 5A)

/// 목적 상세: 뒤에 고정된 목적 색 머리(상태 바 뒤까지), 앞에 후보 목록 바텀시트(`WLHeaderSheet`). 탭 바 보임.
/// 시트는 resting(머리 실제 높이 바로 아래) ↔ expanded(위쪽 바 바로 아래) 두 높이에서 멈추고, 머리는 진행값에 따라
/// 연속으로 변한다(`DemoPurposeSheetHeader`). 후보 0개도 같은 시트에 빈 상태를 담아 같은 동작을 한다.
private struct DemoPurposeDetail: View {
    let purpose: DemoPurpose

    @State private var sheet = WLHeaderSheetState()


    var body: some View {
        DemoSafeTop { safeTop, _ in
            ZStack {
                // 시트 윗변 위로 보이는 영역은 모두 목적 색(상태 바 뒤까지).
                purpose.color.face.ignoresSafeArea()
                WLHeaderSheet(state: sheet, expandedTop: WLTopBarMetrics.bottom(safeTop: safeTop), barBackground: purpose.color.face) { progress in
                    DemoPurposeSheetHeader(purpose: purpose, progress: progress, safeTop: safeTop)
                } bar: { progress in
                    DemoPurposeSheetBar(purpose: purpose, progress: progress, safeTop: safeTop)
                } content: {
                    DemoCandidateList(purpose: purpose)
                }
            }
        }
    }
}

/// 시트 안 후보 목록: "후보 N" + 2열 카드, 0개면 빈 상태(400 높이 가운데).
private struct DemoCandidateList: View {
    let purpose: DemoPurpose

    @Environment(\.wlEntryID) private var entryID

    var body: some View {
        let items = DemoContent.candidates(for: purpose)
        if items.isEmpty {
            EmptyState(title: "아직 후보가 없어요", description: "저장해 둔 상품 중에서 이 목적으로 비교할 후보를 골라 주세요.") {
                DemoLineIcon(icon: .purposeTab, size: 24)
            }
            .wlOnSheet()
            .frame(height: 400)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                WLText("후보", WLTextStyle.bodyBold.resized(15), maxLines: 1)
                WLText(String(items.count), .price, maxLines: 1)
            }
            .padding(.horizontal, WishlistTokens.Space.s4)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Masonry2Col(gap: WishlistTokens.Space.s12, verticalGap: WishlistTokens.Space.s20) {
                ForEach(items, id: \.id) { p in
                    DemoProductCard(product: p, sourceKey: "entry\(entryID ?? -1)/purpose/\(p.id)")
                }
            }
        }
    }
}

/// 목적 상세 머리 값(보드, 상태 바 44 기준): 위 52, 줄 간격 16, 버튼 44, 좌우 20, 윗줄 간격 10.
private enum DemoPurposeHeaderLayout {
    /// 맨 윗줄 버튼 윗변(안전 영역 아래에서). 위쪽 바(`WLTopBarMetrics`)와 같은 자리다.
    static let top: CGFloat = WLTopBarMetrics.buttonTop(safeTop: 0)
    static let gap: CGFloat = 16
    static let button: CGFloat = WLTopBarMetrics.button
    static let side: CGFloat = WLTopBarMetrics.side
    static let rowGap: CGFloat = 10
}

/// 목적 상세 머리 중 시트 뒤 층: 설명·알약 버튼(맨 윗줄과 이름 줄은 자리만 둔다). 이 층의 높이가 resting이다.
/// 설명·알약은 제자리에서 시트에 덮이며 p 0 → 0.5 동안 옅어진다(그 뒤 누르기·접근성 제외).
private struct DemoPurposeSheetHeader: View {
    let purpose: DemoPurpose
    let progress: CGFloat
    let safeTop: CGFloat

    private typealias L = DemoPurposeHeaderLayout

    var body: some View {
        let p = progress
        let fade = max(0, 1 - p / 0.5)
        VStack(alignment: .leading, spacing: L.gap) {
            Color.clear.frame(height: L.button)
            VStack(alignment: .leading, spacing: 2) {
                Color.clear.frame(height: L.button)
                if let description = purpose.description {
                    // 보드 p: 위 8, 높이 21.75 = padding 3 + 줄 15 + 2.75 + 아래 1.
                    WLText(description, WLTextStyle.body.resized(15, lineHeight: 15), color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                        .padding(.top, WishlistTokens.Space.s8 + 3)
                        .padding(.bottom, 3.75)
                        .opacity(fade)
                        .accessibilityHidden(p >= 0.5)
                }
            }
            HStack(spacing: WishlistTokens.Space.s8) {
                DemoPillButton(text: "후보 추가", icon: .plus, iconLineWidth: 2, inverse: false) {}
                if purpose.candidates > 0 {
                    DemoPillButton(text: "비교 끝내기", icon: .archive, iconLineWidth: 1.8, inverse: true) {}
                }
            }
            .opacity(fade)
            .allowsHitTesting(p < 0.5)
            .accessibilityHidden(p >= 0.5)
        }
        .padding(.top, safeTop + L.top)
        .padding(.horizontal, L.side)
        .padding(.bottom, 28)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 목적 상세 머리 중 시트 앞 층. 덧씌운 띠 없이 있던 요소가 진행값 p(0 = resting, 1 = expanded)에 따라 연속으로 변한다.
/// - 뒤로·⋯: 맨 윗줄 제자리(좌우 20).
/// - 아이콘 타일 44/14 → 36/12, 이름 도현 28 → 20(크기 보간은 scale): 이름 줄에서 맨 윗줄 뒤로 오른쪽(간격 10·8)으로 올라간다.
///   p = 1에서 이름은 "+" 왼쪽 10에서 끝난다(한 줄, 말줄임). 타일·이름은 어느 진행값에서도 시트 윗변보다 위다.
/// - "+"(후보 추가): ⋯ 왼쪽 10에 p 0.5 → 1 동안 나타난다(opacity, scale .9 → 1). p < 0.5면 누르기·접근성 제외.
private struct DemoPurposeSheetBar: View {
    let purpose: DemoPurpose
    let progress: CGFloat
    let safeTop: CGFloat

    @Environment(\.wlColors) private var c

    private typealias L = DemoPurposeHeaderLayout

    var body: some View {
        let p = progress
        let appear = min(1, max(0, (p - 0.5) / 0.5))
        // 위쪽 바 자리(안전 영역을 무시하는 층이라 safeTop만큼 띄운다).
        WLTopBar {
            DemoBarBack()
        } center: {
            EmptyView()
        } trailing: {
            HStack(spacing: L.rowGap) {
                DemoCircleButton(description: "후보 추가", action: {}) { DemoLineIcon(icon: .plus, size: 18, lineWidth: 2) }
                    .opacity(appear)
                    .scaleEffect(0.9 + 0.1 * appear)
                    .allowsHitTesting(p >= 0.5)
                    .accessibilityHidden(p < 0.5)
                DemoMoreButton()
            }
        }
        .padding(.top, safeTop)
        .frame(maxWidth: .infinity, alignment: .top)
        .overlay(alignment: .topLeading) {
            GeometryReader { geo in title(p, width: geo.size.width) }
        }
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    /// 진행값에 따라 옮겨 그리는 타일·이름. 위치는 resting 이름 줄 ↔ 맨 윗줄 사이 보간이다.
    private func title(_ p: CGFloat, width w: CGFloat) -> some View {
        let topRowCenter = safeTop + L.top + L.button / 2
        let nameRowCenter = topRowCenter + L.button + L.gap
        let cy = lerp(nameRowCenter, topRowCenter, p)
        let tile = lerp(44, 36, p)
        // 가로는 빨리(ease-out), 세로는 진행값대로 움직여 올라가는 타일이 뒤로 버튼과 겹치지 않게 한다.
        let across = 1 - pow(1 - min(1, p / 0.6), 3)
        let tileX = lerp(L.side, L.side + L.button + L.rowGap, across)
        let nameX = tileX + tile + lerp(12, 8, p)
        // p = 1: "+"(⋯ 왼쪽 10) 왼쪽 10에서 끝난다.
        let nameRight = lerp(w - L.side, w - L.side - L.button * 2 - L.rowGap * 2, p)
        let scale = lerp(1, 20.0 / 28, p)
        let available = max(1, nameRight - nameX)
        return ZStack(alignment: .topLeading) {
            WLIconTile(size: tile, radius: lerp(WishlistTokens.Radius.s, 12, p), color: c.card) {
                DemoLineIcon(icon: purpose.icon, size: lerp(22, 18, p))
            }
            .accessibilityHidden(true)
            .offset(x: tileX, y: cy - tile / 2)
            // 보드 h1(높이 28 = padding 3 + 줄 상자 24 + 1): 글자 상자 중심이 줄 가운데보다 1 아래.
            WLText(purpose.name, .display28Edit, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                .frame(width: available / scale, height: 24, alignment: .leading)
                .scaleEffect(scale, anchor: .leading)
                .offset(x: nameX, y: cy - 12 + (1 - p))
                .wlArrivalFocus()
        }
        .allowsHitTesting(false)
    }
}

private struct DemoBarBack: View {
    @Environment(\.wlNavigator) private var nav

    var body: some View {
        DemoCircleButton(description: "뒤로", action: { nav.pop() }) { BackChevron() }
    }
}

/// 목적 상세 머리 버튼(높이 44, padding 0/18/0/14, 아이콘 18 + 6 + 15/500). `inverse`는 먹색 면.
private struct DemoPillButton: View {
    let text: String
    let icon: DemoIcon
    let iconLineWidth: CGFloat
    let inverse: Bool
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        let fg = inverse ? c.onInverse : c.text
        Button(action: action) {
            HStack(spacing: 6) {
                DemoLineIcon(icon: icon, size: 18, lineWidth: iconLineWidth, color: fg)
                WLText(text, WLTextStyle.bodyStrong.resized(15), color: fg, maxLines: 1)
            }
            .padding(.leading, 14)
            .padding(.trailing, 18)
            .frame(minHeight: WishlistTokens.Space.minTouch)
            .background(inverse ? c.text : c.card, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

#endif
