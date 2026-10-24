import Shared
import SwiftUI

/// The flow screen route (C7): one entry owner, start once, foreground while visible, and the review or
/// fill screen by kind. Errors before the first card show retry; a later failed read keeps the cards.
struct HomeFlowScreen: View {
    let target: HomeFlowTarget

    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlNavigator) private var nav
    @Environment(\.scenePhase) private var phase
    @Environment(\.wlColors) private var c

    var body: some View {
        let owner = owners.homeFlow(entryID ?? -1)
        Group {
            switch target.kind {
            case .review: ReviewFlowScreen(owner: owner)
            case .fill: FillFlowScreen(owner: owner)
            }
        }
        // Board: the bottom buttons sit 36 above the screen edge (the home indicator is inside that 36).
        .ignoresSafeArea(.container, edges: .bottom)
        .background(c.background.ignoresSafeArea())
        .onAppear { owner.startOnce(target) }
        .onChange(of: phase) { _, phase in
            owner.scenePhaseChanged(phase, visible: HomeFlowOwner.isVisible(entryID: entryID, navigator: nav))
        }
    }
}

/// Board header (FHomeReviewFlow / FHomeFillFlow): close 44 · title 16/700 centered · "n / 전체" 14/500 in a
/// 44 column (empty on the summary). It never moves with the content.
struct FlowHeader: View {
    let title: String
    let position: String?

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: WishlistTokens.Space.s8) {
            WLCircleButton(.close, label: String(localized: "wl.close")) { nav.pop() }
            WLText(title, WLTextStyle.bodyBold.resized(16), alignment: .center, maxLines: 1)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)
                .wlArrivalFocus()
            WLText(position ?? "", WLTextStyle.bodyStrong, color: c.textSecondary, alignment: .trailing, maxLines: 1)
                .fixedSize()
                .frame(minWidth: WishlistTokens.Space.minTouch, alignment: .trailing)
                .accessibilityHidden(position == nil)
        }
        .padding(.horizontal, WishlistTokens.Space.s16)
        .frame(height: WLTopBarMetrics.height)
        .padding(.top, WLTopBarMetrics.topGap)
    }
}

/// The end summary (board done state): done tile 64, title 24/700, the session count 15, 홈으로, 처음부터 다시
/// 보기. It rises like the next card stack (scale .94 → 1, 300 `emphasized`).
struct FlowSummary: View {
    let title: String
    let summary: String
    let restart: () -> Void

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlColors) private var c
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    var body: some View {
        let dark = scheme == .dark
        VStack(spacing: 14) {
            WLIconTile(size: 64, radius: WishlistTokens.Radius.l, color: dark ? WishlistTokens.Status.Dark.doneSurface : WishlistTokens.Status.Light.doneSurface) {
                WLIcon(.checkBold, size: 28, color: dark ? WishlistTokens.Status.Dark.doneIcon : WishlistTokens.Status.Light.doneIcon)
            }
            .accessibilityHidden(true)
            WLText(title, WLTextStyle.title.resized(24), alignment: .center)
                .accessibilityAddTraits(.isHeader)
            WLText(summary, WLTextStyle.body.resized(15), color: c.textSecondary, alignment: .center)
            Button { nav.pop() } label: {
                WLText(String(localized: "flow.home"), .button, color: c.onInverse, maxLines: 1)
                    .padding(.horizontal, 28)
                    .frame(height: 52)
                    .background(c.text, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .padding(.top, WishlistTokens.Space.s12)
            Button(action: restart) {
                WLText(String(localized: "flow.restart"), WLTextStyle.body, color: c.textSecondary, maxLines: 1)
                    .frame(minHeight: WishlistTokens.Space.minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, WishlistTokens.Space.s24)
        .padding(.bottom, 120)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .scaleEffect(shown ? 1 : CardThrowMath.nextScale)
        .onAppear {
            withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: CardThrowMath.nextMillis)) { shown = true }
        }
    }
}

/// Before the first card: a spinner, or the first read's error with retry (the Presenter's ERROR phase).
struct FlowStatus: View {
    let state: HomeFlowState
    let retry: () -> Void

    var body: some View {
        VStack {
            if state.phase == .error, let error = state.error {
                DetailStatusBlock(message: DetailLine(DetailText.errorKey(error)).resolve(), buttonText: String(localized: "detail.retry"), action: retry)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, 120)
    }
}

extension HomeFlowState {
    /// The header counter while a card shows (nil on the summary and before the first card).
    var positionText: String? {
        current == nil ? nil : HomeTodoText.position(Int(position), of: Int(total))
    }
}
