import Shared
import SwiftUI

/// FHome "비교 중인 목적" (C7): label 13/500 + "목적 전체" 14 (→ 목적 탭), then HOME-01's recent purposes as
/// sent (at most 3, empty ones too). A card (padding 14·16, r28, purpose color): up to 3 overlapping preview
/// avatars (44, r14, purpose-colored 2 border, −16 apart; the purpose icon when there are none), name Do Hyeon 20,
/// "후보 n · {when} {what}" 12/500, chevron. Tapping opens the purpose detail on this tab.
struct HomePurposeSummary: View {
    let purposes: [PurposeSummary]

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            HStack {
                WLText(String(localized: "home.purposes"), HomeStyles.sectionLabel, color: c.textSecondary)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                Button { nav.selectTab(.purpose) } label: {
                    WLText(String(localized: "home.purposes.all"), WLTextStyle.body, color: c.textSecondary, maxLines: 1)
                        .frame(minHeight: WishlistTokens.Space.minTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            ForEach(purposes.prefix(3), id: \.id) { purpose in
                HomePurposeCard(purpose: purpose)
            }
        }
        .padding(.top, WishlistTokens.Space.s32)
    }
}

private struct HomePurposeCard: View {
    let purpose: PurposeSummary

    @Environment(\.wlNavigator) private var nav

    var body: some View {
        let face = PurposeStyle.color(purpose.colorKey)
        Button {
            nav.push(AppDestination.purpose(purpose.id).route, sourceKey: "home/purpose/\(purpose.id)")
        } label: {
            HStack(spacing: 14) {
                avatars(face)
                VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                    WLText(purpose.name, .display20, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                    TimelineView(.everyMinute) { context in
                        WLText(activity(now: context.date), WLTextStyle.label, color: WishlistTokens.Purpose.onPurpose, maxLines: 1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                WLIcon(.chevronRight, size: 18, color: WishlistTokens.Purpose.onPurpose)
            }
            .padding(.vertical, 14)
            .padding(.horizontal, WishlistTokens.Space.s16)
            .background(face, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func avatars(_ face: Color) -> some View {
        if purpose.previews.isEmpty {
            PurposeIconTile(key: purpose.iconKey, size: 44)
        } else {
            HStack(spacing: -16) {
                ForEach(purpose.previews.prefix(3), id: \.itemId) { preview in
                    ProductPhoto(imageUrl: preview.imageUrl, fill: false, inset: 5)
                        .frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .padding(2)
                        .background(face, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.s, style: .continuous))
                }
            }
            .accessibilityHidden(true)
        }
    }

    private func activity(now: Date) -> String {
        let relative = DisplayFormat.shared.relative(from: purpose.activity.at, now: DetailClock.instant(now), utcOffsetSeconds: DetailClock.utcOffset)
        let action = String(localized: purpose.activity.kind == "CANDIDATE_ADDED" ? "purpose.activity.added" : "purpose.activity.created")
        return String(format: String(localized: "purpose.activity"), purpose.candidateCount, HomeRowText.time(relative).resolve(), action)
    }
}
