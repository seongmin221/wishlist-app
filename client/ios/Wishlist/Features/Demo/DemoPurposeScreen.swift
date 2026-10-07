import SwiftUI

#if DEBUG

/// 목적 탭 데모(FPurposeHomeL): 겹쳐 쌓인 목적 카드 더미. 하나만 펼쳐져 상품 썸네일을 보인다.
/// 접힌 카드를 누르면 그 카드가 펼쳐지고(300 `emphasized`, 구현 기본값), 펼친 카드를 한 번 더 누르면 목적 상세로 간다
/// (가로 밀기). 아래에 점선 "목적 추가"와 "끝난 비교" 아카이브 카드가 있다.
struct DemoPurposeScreen: View {
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var open = 0

    private let purposes = DemoContent.purposes

    /// 보드 값: 두 번째 카드부터 위로 22 겹침, 카드 모서리 28, 겹친 경계선 2(바탕색).
    private enum Layout {
        static let overlap: CGFloat = 22
        static let border: CGFloat = 2
    }

    var body: some View {
        WLTabScrollView(tab: .purpose) {
            VStack(alignment: .leading, spacing: 0) {
                DemoTabHeader(title: "목적", subtitle: "비교 중 \(purposes.count)개 · 최근 활동순")
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                    .padding(.bottom, WishlistTokens.Space.s20)
                // 뒤 카드가 앞 카드 아래를 덮는다(VStack은 나중 자식을 위에 그린다).
                VStack(spacing: -Layout.overlap) {
                    ForEach(Array(purposes.enumerated()), id: \.element.id) { i, p in
                        card(p, isOpen: i == open) {
                            if i == open {
                                nav.push(DemoDestination.purpose(p.id).route, sourceKey: key(p))
                            } else {
                                withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: Self.expandMillis)) { open = i }
                            }
                        }
                    }
                }
                .padding(.horizontal, WishlistTokens.Space.s16)
                addButton
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                    .padding(.top, WishlistTokens.Space.s20)
                archiveSection
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                    .padding(.top, WishlistTokens.Space.s32)
                    // 보드 아래 여백 140 − `WLTabScrollView`가 탭 바 몫으로 비우는 104.
                    .padding(.bottom, 140 - (wlTabBarHeight + wlTabBarBottomGap + WishlistTokens.Space.s16))
            }
        }
    }

    /// 목적 카드 펼침 시간(motion.md 구현 기본값 "목적 카드 펼침 300 emphasized").
    static let expandMillis = 300

    private func key(_ p: DemoPurpose) -> String { "purpose/card/\(p.id)" }

    private func card(_ p: DemoPurpose, isOpen: Bool, action: @escaping () -> Void) -> some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous)
        return Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                head(p)
                if isOpen { expandedBody(p) }
            }
            .padding(Layout.border)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(p.color.face, in: shape)
            .overlay(shape.strokeBorder(c.background, lineWidth: Layout.border))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(isOpen ? "목적 열기" : "펼치기")
    }

    /// 머리 줄: 아이콘 타일 36 + 12 + 이름 도현 28 ↔ 개수 26/700(padding 14/20/30/14, min-height 64).
    private func head(_ p: DemoPurpose) -> some View {
        HStack(spacing: 10) {
            HStack(spacing: WishlistTokens.Space.s12) {
                DemoPurposeTile(icon: p.icon)
                WLText(p.name, .display28, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            WLText(String(p.candidates), WLTextStyle.price.resized(26), color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
        }
        .padding(EdgeInsets(top: 14, leading: 14, bottom: 30, trailing: 20))
        .frame(minHeight: 64)
    }

    /// 펼친 본문: 메타 12/500 + 14 + 썸네일 4칸(정사각, 모서리 14, padding 10). padding 0/20/40, 위로 16 겹침.
    private func expandedBody(_ p: DemoPurpose) -> some View {
        let items = DemoContent.candidates(for: p)
        return VStack(alignment: .leading, spacing: 14) {
            WLText(p.meta, .label, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
            if !items.isEmpty {
                HStack(spacing: WishlistTokens.Space.s8) {
                    ForEach(0..<4, id: \.self) { i in
                        Group {
                            if i < items.count {
                                // 보드 썸네일은 그림이 padding 10 안을 채운다(그림 비율 ≈ 0.75).
                                DemoPhoto(art: items[i].art, iconScale: 0.75)
                                    .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.s, style: .continuous))
                            } else {
                                Color.clear
                            }
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(.horizontal, WishlistTokens.Space.s20)
        .padding(.bottom, WishlistTokens.Space.s40)
        .padding(.top, -16)
        .transition(.opacity)
    }

    /// 점선 "목적 추가"(전체 폭, min-height 56, 모서리 28, 1.5 점선, + 18, 16/700). 지금은 동작 없음.
    private var addButton: some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous)
        return Button {} label: {
            HStack(spacing: WishlistTokens.Space.s8) {
                DemoLineIcon(icon: .plus, size: 18, lineWidth: 2)
                WLText("목적 추가", .button, maxLines: 1)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .overlay(shape.inset(by: 0.75).stroke(c.textSecondary, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
    }

    /// "끝난 비교": 아카이브 카드(min-height 64, 좌우 16, 모서리 28, 카드 면). 지금은 동작 없음.
    private var archiveSection: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            WLText("끝난 비교", .demoCaption, color: c.textSecondary, maxLines: 1)
                .accessibilityAddTraits(.isHeader)
            WLCard(onClick: {}) {
                HStack(spacing: WishlistTokens.Space.s12) {
                    WLIconTile { DemoLineIcon(icon: .archive, size: 18) }
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            WLText("아카이브", .button, maxLines: 1)
                            WLText("2", WLTextStyle.price.resized(16), maxLines: 1)
                        }
                        WLText("겨울 패딩 · 기계식 키보드", .label, color: c.textSecondary, maxLines: 1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    DemoLineIcon(icon: .chevronRight, size: 18)
                }
                .padding(.horizontal, WishlistTokens.Space.s16)
                .frame(maxWidth: .infinity, minHeight: 64)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

#endif
