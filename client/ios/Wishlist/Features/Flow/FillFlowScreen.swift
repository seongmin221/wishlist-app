import Shared
import SwiftUI

/// FHomeFillFlow (정보 보완 연속 처리, C7). Only 건너뛰기 works (a temporary pass): the board's 다시 분석하기,
/// photo, name and category inputs and 저장하고 다음 are drawn disabled with the next-step note (C8). 원본 opens
/// the in-app web view (C4). End summary `건너뜀 n` with 홈으로 and 처음부터 다시 보기.
///
/// Motion (§5): header and bottom buttons stay; only the content between them slides (current 0 → −W, next
/// +W → 0, 320 `standard`, clipped). The counter changes at the start, the keyboard is dismissed first,
/// the next content starts at its top (a new scroll view), and the buttons ignore taps during the 320.
struct FillFlowScreen: View {
    let owner: HomeFlowOwner

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var locked = false
    @State private var barWidth: CGFloat = 0

    var body: some View {
        let state = owner.state
        VStack(spacing: 0) {
            FlowHeader(title: String(localized: "home.group.fill.title"), position: state.positionText)
            if state.phase == .done {
                FlowSummary(title: String(localized: "flow.fill.done.title"),
                            summary: HomeTodoText.skippedSummary(Int(state.passed)), restart: owner.restart)
            } else if let current = state.current {
                ZStack {
                    FillContent(item: current.item)
                        .id(current.item.id)
                        .transition(.asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading)))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
                .animation(reduceMotion ? nil : WishlistTokens.Curve.standard.animation(ms: FillFlowMotion.slideMillis), value: current.item.id)
                bottomBar
            } else {
                FlowStatus(state: state, retry: owner.retry)
            }
        }
    }

    /// 건너뛰기 (flex 1, card color) · 저장하고 다음 (flex 2, ink, disabled until C8), with the C8 note above.
    private var bottomBar: some View {
        VStack(spacing: WishlistTokens.Space.s8) {
            WLText(String(localized: "flow.fill.notice"), WLTextStyle.body.resized(13), color: c.textSecondary, alignment: .center)
                .frame(maxWidth: .infinity)
            HStack(spacing: 10) {
                Button(action: skip) {
                    WLText(String(localized: "flow.fill.skip"), .buttonMedium, color: c.text, maxLines: 1)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(c.card, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .frame(width: barWidth > 0 ? (barWidth - 10) / 3 : nil)
                Button {} label: {
                    WLText(String(localized: "flow.fill.save"), .button, color: c.onInverse, maxLines: 1)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(c.text, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(true)
                .opacity(wlDisabledOpacity)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { barWidth = $0 }
            .allowsHitTesting(!locked)
        }
        .padding(.init(top: WishlistTokens.Space.s12, leading: WishlistTokens.Space.s20, bottom: 36, trailing: WishlistTokens.Space.s20))
        .background(c.background)
    }

    private func skip() {
        guard !locked, owner.state.phase == .active else { return }
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        owner.pass()
        guard !reduceMotion else { return }
        locked = true
        // Unlocks when the 320 slide has run (a display delay, not a read).
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(FillFlowMotion.slideMillis))
            locked = false
        }
    }
}

enum FillFlowMotion {
    static let slideMillis = 320
}

/// One item's fill content (board order): reason card with 원본, 다시 분석하기 + "또는 직접 입력" (failed analysis
/// only), 대표 이미지 · 선택, 제품명 · 필수, 카테고리 · 필수. Inputs are disabled in C7.
private struct FillContent: View {
    let item: WishlistItem

    @Environment(\.wlColors) private var c
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s20) {
                reason
                if item.requiredAction == .informationCompletion {
                    disabled {
                        HStack(spacing: WishlistTokens.Space.s8) {
                            WLIcon(.reload, size: 18)
                            WLText(String(localized: "flow.fill.reanalyze"), .buttonMedium, maxLines: 1)
                        }
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(c.card, in: Capsule())
                    }
                    HStack(spacing: 10) {
                        Rectangle().fill(c.line).frame(height: 1)
                        WLText(String(localized: "flow.fill.or"), WLTextStyle.body.resized(13), color: c.textSecondary, maxLines: 1).fixedSize()
                        Rectangle().fill(c.line).frame(height: 1)
                    }
                }
                field(String(localized: "flow.fill.image")) { image }
                field(String(localized: "flow.fill.name")) {
                    disabled {
                        let name = item.product.name.flatMap { $0.isEmpty ? nil : $0 }
                        WLText(name ?? String(localized: "flow.fill.name.placeholder"), WLTextStyle.buttonRegular,
                               color: name == nil ? c.textSecondary : c.text, maxLines: 1)
                            .padding(.horizontal, 18)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                    }
                }
                field(String(localized: "flow.fill.category")) {
                    disabled {
                        HStack {
                            WLText(String(localized: "flow.fill.category.select"), WLTextStyle.buttonRegular, color: c.textSecondary, maxLines: 1)
                            Spacer(minLength: 0)
                            WLIcon(.chevronRight, size: 18)
                        }
                        .padding(.leading, 18)
                        .padding(.trailing, 14)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                    }
                }
            }
            .padding(.init(top: WishlistTokens.Space.s12, leading: WishlistTokens.Space.s20, bottom: WishlistTokens.Space.s24, trailing: WishlistTokens.Space.s20))
        }
        .scrollDismissesKeyboard(.immediately)
        .background(c.background)
    }

    /// Reason card (padding 16, r28): failed tile with pen, reason 15/700, host 12/500, 원본 → web view.
    private var reason: some View {
        let tile = DetailTile.failed(scheme == .dark)
        return HStack(spacing: WishlistTokens.Space.s12) {
            WLIconTile(color: tile.surface) { WLIcon(.pen, color: tile.icon) }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                WLText(String(localized: String.LocalizationValue(HomeTodoText.fillReasonKey(item.requiredAction))), WLTextStyle.bodyBold.resized(15))
                WLText(HomeTodoText.host(item.sourceUrl), .label, color: c.textSecondary, maxLines: 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            OriginalLink(url: item.sourceUrl, sourceKey: "flow/fill/\(item.id)/original")
        }
        .padding(WishlistTokens.Space.s16)
        .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous))
    }

    @ViewBuilder private var image: some View {
        if remotePhotoURL(item.product.imageUrl) != nil {
            ProductPhoto(imageUrl: item.product.imageUrl, fill: false, inset: 14)
                .frame(width: 120, height: 120)
                .background(c.card, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
        } else {
            disabled {
                VStack(spacing: 6) {
                    Image(systemName: "photo").font(.system(size: 20)).foregroundStyle(c.textSecondary)
                    WLText(String(localized: "flow.fill.image.add"), .body, color: c.textSecondary, maxLines: 1)
                }
                .frame(maxWidth: .infinity, minHeight: 120)
                .overlay(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)
                    .strokeBorder(c.underline, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3])))
            }
        }
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            WLText(label, WLTextStyle.body.resized(13), color: c.textSecondary)
            content()
        }
    }

    /// A C8 control drawn per board but unavailable (dimmed, VoiceOver "dimmed").
    private func disabled<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        Button {} label: { content() }
            .buttonStyle(.plain)
            .disabled(true)
            .opacity(wlDisabledOpacity)
    }
}
