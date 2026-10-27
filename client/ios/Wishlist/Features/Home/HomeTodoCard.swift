import Shared
import SwiftUI

/// 할 일 카드(분석 대기·분류 중·분류·목적 확인·정보 보완): 접힌 채 시작한다. 오른쪽 화살표가 펼치기·접기다(200 `ease`
/// 180°, 보드 이름 "펼치기"/"접기"). 카드 머리는 `open`이 있으면 그 동작(연속 처리 열기, FHome의 카드 영역 링크),
/// 없으면 화살표와 같은 펼치기다(분석 대기·분류 중: 갈 화면이 없다). 펼침 내용 높이·opacity 200 `ease`(motion.md 작은 동작).
struct HomeTodoCard<Rows: View>: View {
    let icon: WLLineIcon
    let title: String
    let subtitle: String
    let count: Int
    @Binding var expanded: Bool
    var open: (() -> Void)?
    /// 펼침 내용의 안쪽 여백(줄 목록 12, 캐러셀 0: 캐러셀이 직접 16을 준다).
    var contentInset: CGFloat = 12
    @ViewBuilder var rows: () -> Rows

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        WLCard {
            VStack(spacing: 0) {
                HStack(spacing: WishlistTokens.Space.s4) {
                    Button { if let open { open() } else { expanded.toggle() } } label: {
                        HStack(spacing: 14) {
                            WLIconTile { WLIcon(icon) }
                            VStack(alignment: .leading, spacing: 2) {
                                WLText(title, HomeStyles.cardTitle)
                                WLText(subtitle, HomeStyles.cardSubtitle, color: c.textSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            WLText("\(count)", HomeStyles.count, maxLines: 1)
                        }
                        .frame(minHeight: WishlistTokens.Space.minTouch)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .modifier(HomeTodoHeadAccessibility(opens: open != nil, expanded: expanded))
                    Button { expanded.toggle() } label: {
                        WLChevron(expanded: expanded)
                            .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: expanded ? "home.collapse" : "home.expand"))
                }
                .padding(.init(top: 16, leading: 16, bottom: 16, trailing: 6))
                WLDisclosureLayout(progress: expanded ? 1 : 0) {
                    VStack(spacing: WishlistTokens.Space.s8) { rows() }
                        .padding(.horizontal, contentInset)
                        .padding(.bottom, contentInset)
                }
                .clipped()
                .opacity(expanded ? 1 : 0)
                .allowsHitTesting(expanded)
                .accessibilityHidden(!expanded)
            }
            .animation(reduceMotion ? nil : WishlistTokens.Curve.ease.animation(ms: WishlistTokens.Motion.disclosureContent), value: expanded)
        }
    }
}

/// VoiceOver: a head that opens a flow says so as its hint; a head that expands carries the expanded value.
private struct HomeTodoHeadAccessibility: ViewModifier {
    let opens: Bool
    let expanded: Bool

    func body(content: Content) -> some View {
        if opens {
            content.accessibilityHint(String(localized: "home.group.open"))
        } else {
            content.accessibilityValue(String(localized: expanded ? "wl.expanded" : "wl.collapsed"))
        }
    }
}

/// One HOME-01 group card (C7): review and fill open their flow from the head (first card) or a preview (that
/// preview's anchor); 분류 중 only expands, and its previews open the item detail. Previews: at most 4, a
/// horizontal carousel (92 photo r20, name 13/1.35, caption 11/500), placeholder photo when there is none.
struct HomeGroupTodoCard: View {
    let card: HomeGroupCard
    @Binding var expanded: Bool

    @Environment(\.wlNavigator) private var nav

    var body: some View {
        let kind = HomeFlowTarget.Kind(group: card.group)
        HomeTodoCard(
            icon: icon,
            title: String(localized: String.LocalizationValue(HomeTodoText.groupTitleKey(card.group))),
            subtitle: String(localized: String.LocalizationValue(HomeTodoText.groupSubtitleKey(card.group))),
            count: Int(card.count),
            expanded: $expanded,
            open: kind.map { kind in { openFlow(kind, anchor: nil) } },
            contentInset: 0
        ) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: WishlistTokens.Space.s12) {
                    ForEach(card.previews.prefix(4), id: \.item.id) { preview in
                        HomePreviewTile(preview: preview, group: card.group) {
                            if let kind {
                                openFlow(kind, anchor: preview.anchorCursor)
                            } else {
                                nav.push(AppDestination.item(preview.item.id).route, sourceKey: "home/preview/\(preview.item.id)")
                            }
                        }
                    }
                }
                .padding(.horizontal, WishlistTokens.Space.s16)
                .padding(.bottom, WishlistTokens.Space.s16)
            }
        }
    }

    private var icon: WLLineIcon {
        switch card.group {
        case .classificationReview: .pending
        case .informationCompletion: .pen
        default: .sorting
        }
    }

    private func openFlow(_ kind: HomeFlowTarget.Kind, anchor: String?) {
        let target = HomeFlowTarget(kind: kind, startAnchor: anchor)
        nav.push(AppDestination.homeFlow(target).route, sourceKey: "home/flow/\(card.group.name)/\(anchor ?? "first")")
    }
}

/// A carousel preview (board FHome): 92 square photo r20, name 13/1.35 (two lines), caption 11/500. A fill item
/// without a photo shows the board's dashed "사진 없음" box; others use the product placeholder.
private struct HomePreviewTile: View {
    let preview: HomePreview
    let group: HomeActionGroup
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        let item = preview.item
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                photo(item)
                    .frame(width: 92, height: 92)
                    .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                WLText(HomeTodoText.title(item), WLTextStyle.body.resized(13, lineHeight: 13 * 1.35),
                       color: item.product.name == nil ? c.textSecondary : c.text, maxLines: 2)
                WLText(HomeTodoText.previewCaption(item, group: group), WLTextStyle.label.resized(11), color: c.textSecondary, maxLines: 1)
            }
            .frame(width: 92, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func photo(_ item: WishlistItem) -> some View {
        if group == .informationCompletion && remotePhotoURL(item.product.imageUrl) == nil {
            WLText(String(localized: "home.preview.no.photo"), WLTextStyle.body.resized(12), color: c.textSecondary, alignment: .center, maxLines: 1)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)
                    .strokeBorder(c.underline, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
        } else {
            ProductPhoto(imageUrl: item.product.imageUrl, fill: false, inset: 12, placeholderColor: c.sheetField)
                .background(c.sheetField)
        }
    }
}
