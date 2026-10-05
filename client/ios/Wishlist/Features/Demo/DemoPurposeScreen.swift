import SwiftUI

/// 목적 탭 데모: 목적 색 면 카드(도현 28) 목록. 카드 → 목적 상세는 자리 표시 면 이동. 맨 아래 가장 긴 목적 이름.
struct DemoPurposeScreen: View {
    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        WLTabScrollView(tab: .purpose) {
            VStack(alignment: .leading, spacing: 0) {
                DemoTabHeader(title: "목적", subtitle: "비교 중 \(DemoContent.purposes.count)개 · 최근 활동순")
                Spacer().frame(height: WishlistTokens.Space.s24)
                VStack(spacing: WishlistTokens.Space.s8) {
                    ForEach(DemoContent.purposes, id: \.id) { p in
                        let key = "purpose/card/\(p.id)"
                        WLSharedSurfaceSource(key: key, fill: .purpose(p.color), radius: .fixed(WishlistTokens.Radius.xl)) {
                            WLCard(radius: WishlistTokens.Radius.xl, onClick: {
                                nav?.push(.demoDetail(id: DemoIds.purpose(p.id), hasPhoto: false), sourceKey: key)
                            }) {
                                HStack(spacing: WishlistTokens.Space.s12) {
                                    WLIconTile(size: 36, radius: 12, color: c.card) {
                                        DemoPhoto(tint: p.color).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    }
                                    WLText(p.name, .display28TwoLine, color: WishlistTokens.Purpose.onPurpose)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    WLText(String(p.candidates), WLTextStyle.price.resized(WLTextStyle.display20.size),
                                           color: WishlistTokens.Purpose.onPurpose)
                                }
                                .padding(WishlistTokens.Space.s16)
                                .frame(maxWidth: .infinity, minHeight: 76)
                                .background(p.color.face)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
        }
    }
}
