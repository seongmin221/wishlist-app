import SwiftUI

/// 홈 탭 데모(debug 빌드): overlay 열기, 사진 카드·칩·목적 카드 push, 가장 긴 목적 이름과 버튼 쌍.
struct DemoHomeScreen: View {
    @Environment(\.wlNavigator) private var nav
    @Environment(\.overlayHostState) private var overlay
    @State private var menuAnchor = WLAnchor()

    var body: some View {
        WLTabScrollView(tab: .home) {
            VStack(alignment: .leading, spacing: 0) {
                DemoTabHeader(title: "홈", subtitle: "데모 · 화면 이동과 overlay") {
                    DemoCircleButton(description: "더 보기", action: {
                        if let overlay { overlay.showMenu(anchor: menuAnchor.frame, items: demoMenuItems(overlay)) }
                    }) { MoreDots() }
                    .wlAnchor(menuAnchor)
                }

                DemoSectionLabel(text: "overlay")
                HStack(spacing: WishlistTokens.Space.s12) {
                    WLButton("시트 열기", kind: .secondary) { overlay?.showSheet { DemoSheet() } }
                    WLButton("확인창 열기", kind: .primary) { overlay?.showDialog(demoDeleteDialog()) }
                }

                DemoSectionLabel(text: "사진 카드 → 상세 (사진이 커짐)")
                HStack(alignment: .top, spacing: WishlistTokens.Space.s12) {
                    ForEach([0, 1], id: \.self) { col in
                        VStack(spacing: WishlistTokens.Space.s16) {
                            ForEach(Array(DemoContent.products.enumerated()).filter { $0.offset % 2 == col }, id: \.element.id) { _, p in
                                DemoProductCard(product: p, sourceKey: "home/product/\(p.id)")
                            }
                        }
                    }
                }

                DemoSectionLabel(text: "사진 없는 칩 → 목록 (면이 커짐)")
                DemoFlowLayout {
                    ForEach(DemoContent.chips.prefix(5), id: \.id) { DemoSurfaceChip(chip: $0, sourceKey: "home/chip/\($0.id)") }
                }

                DemoSectionLabel(text: "비교 중인 목적")
                VStack(spacing: WishlistTokens.Space.s12) {
                    ForEach(DemoContent.purposes.filter { ["commute", "trail", "longest"].contains($0.id) }, id: \.id) { p in
                        let key = "home/purpose/\(p.id)"
                        WLSharedSurfaceSource(key: key, fill: .purpose(p.color), radius: .fixed(WishlistTokens.Radius.xl)) {
                            DemoPurposeRow(purpose: p) {
                                nav?.push(.demoDetail(id: DemoIds.purpose(p.id), hasPhoto: false), sourceKey: key)
                            }
                        }
                    }
                }

                DemoSectionLabel(text: "가장 긴 목적 이름 · 큰 글자")
                WLCard {
                    VStack(alignment: .leading, spacing: WishlistTokens.Space.s16) {
                        WLText(DemoContent.longestPurposeName, .display28TwoLine)
                        WLText(DemoContent.longestPurposeName, .display20TwoLine)
                        WLButtonPair(cancelText: "취소", primaryText: "목적 만들기", primaryKind: .primary, onCancel: {}, onPrimary: {})
                    }
                    .padding(WishlistTokens.Space.s20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
        }
    }
}

/// 사진 카드(사진 → 상품 상세는 사진 공유 요소 이동).
struct DemoProductCard: View {
    let product: DemoProduct
    let sourceKey: String

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
            WLCard(radius: WishlistTokens.Radius.m, onClick: {
                nav?.push(.demoDetail(id: DemoIds.product(product.id), hasPhoto: true), sourceKey: sourceKey)
            }) {
                WLSharedPhotoSource(key: sourceKey) {
                    DemoPhoto(tint: product.tint)
                        .aspectRatio(1 / product.photoRatio, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                }
            }
            .accessibilityLabel("\(product.brand) \(product.name)")
            Spacer().frame(height: WishlistTokens.Space.s4)
            WLText(product.brand, .label, color: c.textSecondary)
            WLText(product.name, .bodyStrong).lineLimit(2)
            PriceText(amount: product.price, currency: product.currency, style: WLTextStyle.price.resized(WLTextStyle.body.size))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 홈의 비교 중인 목적 카드(목적 색 면, 도현 20).
private struct DemoPurposeRow: View {
    let purpose: DemoPurpose
    let action: () -> Void

    var body: some View {
        WLCard(radius: WishlistTokens.Radius.xl, onClick: action) {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                WLText(purpose.name, .display20TwoLine, color: WishlistTokens.Purpose.onPurpose)
                WLText("후보 \(purpose.candidates)", .label, color: WishlistTokens.Purpose.onPurpose)
            }
            .padding(.horizontal, WishlistTokens.Space.s20)
            .padding(.vertical, WishlistTokens.Space.s16)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .background(purpose.color.face)
        }
    }
}

private struct DemoSheet: View {
    @Environment(\.overlayHostState) private var overlay
    @State private var name = ""

    var body: some View {
        VStack(spacing: WishlistTokens.Space.s20) {
            WLSheetHeader(title: "시트 데모") { overlay?.dismiss() }
            WLInput(value: $name, label: "이름", placeholder: DemoContent.longestPurposeName, maxLength: 40)
            WLButtonPair(cancelText: "취소", primaryText: "저장", primaryKind: .primary,
                         onCancel: { overlay?.dismiss() }, onPrimary: { overlay?.dismiss() })
        }
    }
}
