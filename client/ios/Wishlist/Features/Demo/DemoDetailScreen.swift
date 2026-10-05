import SwiftUI

/// 데모 상세(debug 빌드에서만 진입). `id` 앞머리로 상품(사진)·칩 목록·목적 상세를 고른다.
/// 사진 없는 화면(칩 목록·목적)은 바탕을 그리지 않는다: 커진 자리 표시 면이 그 화면의 바탕이다.
struct DemoDetailScreen: View {
    let id: String
    let hasPhoto: Bool

    var body: some View {
        let kind = id.split(separator: ":").first.map(String.init) ?? ""
        let ref = id.split(separator: ":").dropFirst().first.map(String.init) ?? ""
        switch kind {
        case "product" where hasPhoto:
            if let p = DemoContent.product(ref) { DemoProductDetail(product: p) }
        case "purpose":
            DemoPurposeDetail(purpose: DemoContent.purpose(ref))
        default:
            DemoChipList(chip: DemoContent.chip(ref))
        }
    }
}

private struct DemoTopBar: View {
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @State private var anchor = WLAnchor()

    var body: some View {
        HStack {
            // 화면의 뒤로 버튼도 끌어서 뒤로와 같은 pop 전환을 쓴다.
            DemoCircleButton(description: "뒤로", action: { nav?.pop() }) { BackChevron() }
            Spacer()
            DemoCircleButton(description: "더 보기", action: {
                if let overlay { overlay.showMenu(anchor: anchor.frame, items: demoMenuItems(overlay)) }
            }) { MoreDots() }
            .wlAnchor(anchor)
        }
        .padding(.vertical, WishlistTokens.Space.s8)
    }
}

/// 상품 상세(FProductDetail): 큰 사진, 브랜드, 제품명, 가격, 카테고리·목적, 하단 고정 원본 보기. 탭 바 없음.
private struct DemoProductDetail: View {
    let product: DemoProduct

    @Environment(\.wlColors) private var c

    var body: some View {
        ZStack(alignment: .bottom) {
            c.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    DemoTopBar()
                    Spacer().frame(height: WishlistTokens.Space.s8)
                    WLSharedPhotoTarget {
                        DemoPhoto(tint: product.tint).aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity)
                    }
                    Spacer().frame(height: WishlistTokens.Space.s24)
                    WLText(product.brand, .bodyStrong)
                    Spacer().frame(height: WishlistTokens.Space.s8)
                    WLText(product.name, .title).wlArrivalFocus()
                    Spacer().frame(height: WishlistTokens.Space.s8)
                    PriceText(amount: product.price, currency: product.currency, style: WLTextStyle.price.resized(WLTextStyle.title.size))
                    Spacer().frame(height: WishlistTokens.Space.s4)
                    WLText("2일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.", .body, color: c.textSecondary)
                    Spacer().frame(height: WishlistTokens.Space.s24)
                    WLCard {
                        VStack(spacing: 0) {
                            DemoInfoRow(label: "카테고리") { WLText(product.category, .bodyStrong) }
                            DemoInfoRow(label: "목적") {
                                if let purpose = product.purpose {
                                    HStack(spacing: 6) {
                                        PurposeDot(color: purpose.color)
                                        WLText(purpose.name, .bodyStrong)
                                    }
                                } else {
                                    WLText("없음", .body, color: c.textSecondary)
                                }
                            }
                        }
                        .padding(.horizontal, WishlistTokens.Space.s16)
                        .padding(.vertical, WishlistTokens.Space.s8)
                    }
                    Spacer().frame(height: 52 + WishlistTokens.Space.s40)
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
            }
            WLButton("원본 보기", kind: .primary) {}
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.vertical, WishlistTokens.Space.s16)
        }
    }
}

private struct DemoInfoRow<Value: View>: View {
    let label: String
    @ViewBuilder let value: () -> Value

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s16) {
            WLText(label, .body, color: c.textSecondary).frame(maxWidth: .infinity, alignment: .leading)
            value()
        }
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
    }
}

/// 칩 → 세부 유형 상품 목록(사진 없는 이동의 다음 화면). 상품 줄의 사진 → 상품 상세로 한 단계 더 들어간다
/// (iOS 데모만: 스택 깊이 2의 push·pop·끌어서 뒤로 확인용).
private struct DemoChipList: View {
    let chip: DemoChip?

    @Environment(\.wlColors) private var c
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlEntryID) private var entryID

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DemoTopBar()
                Spacer().frame(height: WishlistTokens.Space.s16)
                WLText(chip?.name ?? "목록", .title).wlArrivalFocus()
                Spacer().frame(height: WishlistTokens.Space.s4)
                WLText("상품 \(chip?.count ?? 0)개", .label, color: c.textSecondary)
                Spacer().frame(height: WishlistTokens.Space.s24)
                VStack(spacing: WishlistTokens.Space.s12) {
                    ForEach(DemoContent.products, id: \.id) { p in
                        let key = "entry\(entryID ?? -1)/product/\(p.id)"
                        WLCard(onClick: {
                            nav?.push(.demoDetail(id: DemoIds.product(p.id), hasPhoto: true), sourceKey: key)
                        }) {
                            HStack(spacing: WishlistTokens.Space.s12) {
                                WLSharedPhotoSource(key: key) { DemoPhoto(tint: p.tint) }
                                    .frame(width: 64, height: 64)
                                VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                                    WLText(p.brand, .label, color: c.textSecondary)
                                    WLText(p.name, .bodyStrong)
                                    PriceText(amount: p.price, currency: p.currency, style: WLTextStyle.price.resized(WLTextStyle.body.size))
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .padding(WishlistTokens.Space.s12)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                Spacer().frame(height: WishlistTokens.Space.s40)
            }
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
        }
    }
}

/// 목적 카드 → 목적 상세 머리(목적 색 면, 도현 28 두 줄).
private struct DemoPurposeDetail: View {
    let purpose: DemoPurpose?

    @Environment(\.wlColors) private var c

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    DemoTopBar()
                    Spacer().frame(height: WishlistTokens.Space.s16)
                    WLText(purpose?.name ?? "목적", .display28TwoLine, color: WishlistTokens.Purpose.onPurpose).wlArrivalFocus()
                    Spacer().frame(height: WishlistTokens.Space.s8)
                    WLText("후보 \(purpose?.candidates ?? 0) · 어제 후보 추가", .label, color: WishlistTokens.Purpose.onPurpose)
                }
                .padding(.horizontal, WishlistTokens.Space.screenMargin)
                .padding(.bottom, WishlistTokens.Space.s24)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    UnevenRoundedRectangle(bottomLeadingRadius: WishlistTokens.Radius.xl, bottomTrailingRadius: WishlistTokens.Radius.xl, style: .continuous)
                        .fill(purpose?.color.face ?? c.card)
                        .ignoresSafeArea(edges: .top)
                }
                VStack(spacing: WishlistTokens.Space.s12) {
                    ForEach(DemoContent.products.prefix(2), id: \.id) { pr in
                        WLCard {
                            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                                WLText(pr.brand, .label, color: c.textSecondary)
                                WLText(pr.name, .bodyStrong)
                                PriceText(amount: pr.price, currency: pr.currency)
                            }
                            .padding(WishlistTokens.Space.s16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .padding(WishlistTokens.Space.screenMargin)
            }
        }
    }
}
