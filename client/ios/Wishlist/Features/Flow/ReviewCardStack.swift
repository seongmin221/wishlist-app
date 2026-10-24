import Shared
import SwiftUI

/// FHomeReviewFlow card stack (motion.md §4). 확정 throws right, 보류 left, by button or drag; in C7 both are
/// a temporary pass (`onPass`, nothing saved).
///
/// - Throw: the leaving card is its own layer, `translateX → ±480`, `rotate → ±16°` around (50%, 90%) in
///   340 `throw` (a released drag continues for the part left). The stack under it is already the next one
///   (front = the first upcoming card) and rises `scale .94 → 1`, `translateY 14 → 0`, 40 later, 300 `emphasized`.
///   `onPass` runs when the card is gone; until the Presenter moves on, the leaving id stands for its card.
/// - Drag: `translateX(dx)`, `rotate(dx / W × 16°)`; release follows `CardThrowMath.decide`, else 250 `emphasized` back.
/// - Input (buttons and drag) is ignored while a card leaves. Back cards: at most 2, from the cards left.
struct ReviewCardStack: View {
    let state: HomeFlowState
    let onPass: () -> Void

    @Environment(\.wlColors) private var c
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    #if DEBUG
    @State private var dragX: CGFloat = C7DebugScreens.initialDrag
    #else
    @State private var dragX: CGFloat = 0
    #endif
    @State private var leaving: Leaving?
    @State private var width: CGFloat = 0
    /// The current card id read when a throw ends (the closure outlives the render that made it).
    @State private var shown = ShownCard()

    @MainActor final class ShownCard { var id: String? }

    struct Leaving: Equatable {
        let card: HomeFlowCard
        let direction: CGFloat
        let startDx: CGFloat
        var id: String { card.item.id }
    }

    var body: some View {
        let front = ReviewStackModel.front(current: state.current, upcoming: state.upcoming, leavingID: leaving?.id)
        let backs = ReviewStackModel.backs(position: Int(state.position), total: Int(state.total), frontIsNext: front?.item.id != state.current?.item.id)
        VStack(spacing: 0) {
            ZStack {
                if let front {
                    stack(front, backs: backs)
                        .id(front.item.id)
                        .transition(.asymmetric(insertion: .modifier(active: NextStackEffect(rising: true), identity: NextStackEffect(rising: false)), removal: .identity))
                }
                if let leaving {
                    LeavingCard(leaving: leaving, width: width) {
                        // A foreground rebuild may have replaced the card meanwhile: pass only the thrown one.
                        if ReviewStackModel.shouldPass(currentID: shown.id, leavingID: leaving.id) { onPass() }
                    }
                        .transition(.identity)
                        .zIndex(10)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            Spacer(minLength: WishlistTokens.Space.s16)
            hints
            buttons
        }
        .padding(.init(top: WishlistTokens.Space.s12, leading: WishlistTokens.Space.s20, bottom: 36, trailing: WishlistTokens.Space.s20))
        .onChange(of: state.current?.item.id, initial: true) { _, id in
            shown.id = id
            if let leaving, id != leaving.id { self.leaving = nil }
        }
    }

    // MARK: Stack

    private func stack(_ front: HomeFlowCard, backs: Int) -> some View {
        let back = ReviewBackColors(dark: scheme == .dark)
        return ZStack {
            if backs >= 2 {
                RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous).fill(back.second)
                    .padding(.init(top: 14, leading: 6, bottom: -6, trailing: 6))
                    .rotationEffect(.degrees(3))
            }
            if backs >= 1 {
                RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous).fill(back.first)
                    .padding(.init(top: 8, leading: 2, bottom: -2, trailing: 2))
                    .rotationEffect(.degrees(-2.5))
            }
            ReviewCard(item: front.item)
                // Board `translateX(dx) rotate(θ)` with origin 50% 90%: rotate about the card, then move it.
                .rotationEffect(.degrees(CardThrowMath.rotation(dx: dragX, width: width)), anchor: ReviewStackModel.pivot)
                .offset(x: dragX)
                .gesture(drag)
        }
        .accessibilityElement(children: .contain)
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard leaving == nil else { return }
                dragX = value.translation.width
            }
            .onEnded { value in
                guard leaving == nil else { return }
                switch CardThrowMath.decide(dx: value.translation.width, velocityX: value.velocity.width, width: width) {
                case .right: throwCard(1)
                case .left: throwCard(-1)
                case .return:
                    withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: CardThrowMath.returnMillis)) { dragX = 0 }
                }
            }
    }

    private func throwCard(_ direction: CGFloat) {
        guard leaving == nil, let card = state.current, state.phase == .active else { return }
        let start = dragX
        if reduceMotion {
            dragX = 0
            onPass()
            return
        }
        var still = Transaction()
        still.disablesAnimations = true
        withTransaction(still) { dragX = 0 }
        let rise = WishlistTokens.Curve.emphasized.animation(ms: CardThrowMath.nextMillis)
            .delay(Double(CardThrowMath.nextDelayMillis) / 1000)
        withAnimation(rise) { leaving = Leaving(card: card, direction: direction, startDx: start) }
    }

    // MARK: Hint and buttons

    private var hints: some View {
        VStack(spacing: WishlistTokens.Space.s4) {
            WLText(String(localized: "flow.review.hint"), WLTextStyle.body.resized(13), color: c.textSecondary, alignment: .center)
            WLText(String(localized: "flow.review.notice"), WLTextStyle.body.resized(13), color: c.textSecondary, alignment: .center)
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, WishlistTokens.Space.s12)
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            ReviewActionButton(title: String(localized: "flow.review.hold"), arrow: -1, primary: false) { throwCard(-1) }
            ReviewActionButton(title: String(localized: "flow.review.confirm"), arrow: 1, primary: true) { throwCard(1) }
        }
        .allowsHitTesting(leaving == nil && state.current != nil)
    }
}

/// Which card is in front and how many back cards show (pure).
enum ReviewStackModel {
    /// Rotation center of the card (x 50%, y 90%).
    static let pivot = UnitPoint(x: 0.5, y: 0.9)

    /// The card drawn in front: while the current card is leaving, the next one already is.
    static func front(current: HomeFlowCard?, upcoming: [HomeFlowCard], leavingID: String?) -> HomeFlowCard? {
        guard let current else { return nil }
        return current.item.id == leavingID ? upcoming.first : current
    }

    /// The throw ended: pass only if the card shown is still the one thrown.
    static func shouldPass(currentID: String?, leavingID: String) -> Bool { currentID == leavingID }

    /// Back cards behind the front: the cards left after it, at most 2.
    static func backs(position: Int, total: Int, frontIsNext: Bool) -> Int {
        let after = total - position - (frontIsNext ? 1 : 0)
        return max(0, min(2, after))
    }
}

/// The rising next stack (`scale .94`, `translateY 14` → identity, scaled from 50% 70% like the board).
private struct NextStackEffect: ViewModifier {
    let rising: Bool

    func body(content: Content) -> some View {
        content
            .scaleEffect(rising ? CardThrowMath.nextScale : 1, anchor: UnitPoint(x: 0.5, y: 0.7))
            .offset(y: rising ? CardThrowMath.nextOffset : 0)
    }
}

/// The card on its way out: from the released drag to ±480 / ±16° in the rest of the 340 `throw`.
private struct LeavingCard: View {
    let leaving: ReviewCardStack.Leaving
    let width: CGFloat
    let done: () -> Void

    @State private var x: CGFloat?
    @State private var rotation: Double?

    var body: some View {
        let startRotation = CardThrowMath.rotation(dx: leaving.startDx, width: width)
        ReviewCard(item: leaving.card.item)
            .rotationEffect(.degrees(rotation ?? startRotation), anchor: ReviewStackModel.pivot)
            .offset(x: x ?? leaving.startDx)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onAppear {
                let ms = CardThrowMath.exitMillis(fromDx: leaving.startDx)
                withAnimation(WishlistTokens.Curve.throwAway.animation(ms: ms)) {
                    x = leaving.direction * CardThrowMath.exitDistance
                    rotation = Double(leaving.direction) * CardThrowMath.maxRotation
                } completion: {
                    done()
                }
            }
    }
}

/// The front card face (board article): photo 1:1 r20 (inset 32), name 18/700 one line, price 20/700,
/// category and purpose rows (`변경` disabled until C8), and the AI line (the duplicate line is C10).
struct ReviewCard: View {
    let item: WishlistItem

    @Environment(\.wlColors) private var c

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.l, style: .continuous)
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .overlay { ProductPhoto(imageUrl: item.product.imageUrl, fill: false, inset: 32, placeholderColor: c.photo) }
                .background(c.photo, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                .layoutPriority(-1)
            VStack(alignment: .leading, spacing: WishlistTokens.Space.s4) {
                WLText(HomeTodoText.title(item), WLTextStyle.bodyBold.resized(18, lineHeight: 18 * 1.3), maxLines: 1)
                    .truncationMode(.tail)
                PriceText(amountText: item.product.price?.canonical, currency: item.product.currency, style: WLTextStyle.price.resized(20))
            }
            .padding(.horizontal, WishlistTokens.Space.s4)
            VStack(spacing: 6) {
                ReviewRow(label: String(localized: "flow.review.category")) {
                    if let name = item.category.name {
                        WLText(name, WLTextStyle.bodyBold.resized(15), maxLines: 1)
                    } else {
                        WLText(String(localized: "flow.review.category.none"), WLTextStyle.bodyBold.resized(15), color: c.textSecondary, maxLines: 1)
                    }
                }
                ReviewRow(label: String(localized: "flow.review.purpose")) {
                    if let name = item.purpose.name {
                        HStack(spacing: WishlistTokens.Space.s8) {
                            PurposeColorDot(colorKey: item.purpose.colorKey)
                            WLText(name, WLTextStyle.bodyBold.resized(15), maxLines: 1)
                        }
                    } else {
                        WLText(String(localized: "flow.review.purpose.none"), WLTextStyle.bodyBold.resized(15), color: c.textSecondary, maxLines: 1)
                    }
                }
            }
            HStack {
                if let key = HomeTodoText.aiLineKey(category: item.category.source, purpose: item.purpose.source) {
                    WLText(String(localized: String.LocalizationValue(key)), WLTextStyle.body.resized(13, lineHeight: 13 * 1.4), color: c.textSecondary)
                        .padding(.horizontal, WishlistTokens.Space.s4)
                }
            }
            .frame(height: 44, alignment: .leading)
        }
        .padding(WishlistTokens.Space.s12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(c.card, in: shape)
        .overlay(shape.strokeBorder(c.line, lineWidth: 1.5))
        .contentShape(shape)
    }
}

/// A sheetField row: label 13 (min 52) · value 15/700 · `변경` 14 (disabled, C8).
private struct ReviewRow<Value: View>: View {
    let label: String
    @ViewBuilder var value: () -> Value

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: 10) {
            WLText(label, WLTextStyle.body.resized(13), color: c.textSecondary, maxLines: 1)
                .frame(minWidth: 52, alignment: .leading)
            value().frame(maxWidth: .infinity, alignment: .leading)
            Button {} label: {
                WLText(String(localized: "flow.review.change"), WLTextStyle.body, color: c.textSecondary, maxLines: 1)
                    .padding(.horizontal, WishlistTokens.Space.s12)
                    .frame(minHeight: WishlistTokens.Space.minTouch)
            }
            .buttonStyle(.plain)
            .disabled(true)
            .opacity(wlDisabledOpacity)
        }
        .padding(.leading, 14)
        .padding(.trailing, WishlistTokens.Space.s4)
        .frame(minHeight: WishlistTokens.Space.minTouch)
        .background(c.sheetField, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.s, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// 보류 (card color, ← 16/500) and 확정 (ink, 16/700 →): height 56 pills.
private struct ReviewActionButton: View {
    let title: String
    let arrow: CGFloat
    let primary: Bool
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        let fg = primary ? c.onInverse : c.text
        Button(action: action) {
            HStack(spacing: WishlistTokens.Space.s8) {
                if arrow < 0 { FlowArrow(direction: -1, color: fg) }
                WLText(title, primary ? .button : .buttonMedium, color: fg, maxLines: 1)
                if arrow > 0 { FlowArrow(direction: 1, color: fg) }
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(primary ? c.text : c.card, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// The board's 20pt arrow (M5 12h14 M13 6l6 6-6 6), mirrored for left.
struct FlowArrow: View {
    let direction: CGFloat
    let color: Color

    var body: some View {
        Path { p in
            p.move(to: CGPoint(x: 5, y: 12)); p.addLine(to: CGPoint(x: 19, y: 12))
            p.move(to: CGPoint(x: 13, y: 6)); p.addLine(to: CGPoint(x: 19, y: 12)); p.addLine(to: CGPoint(x: 13, y: 18))
        }
        .stroke(color, style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
        .frame(width: 24, height: 24)
        .scaleEffect(x: direction, y: 1)
        .frame(width: 20, height: 20)
        .accessibilityHidden(true)
    }
}

/// Back card faces (board: light #EEEEEE / #E4E4E4, dark #2A2A2A / #262626). The first matches the light
/// photo and dark sheet tokens; no token holds the second grey, so it stays the board value.
private struct ReviewBackColors {
    let first: Color
    let second: Color

    init(dark: Bool) {
        first = dark ? WishlistTokens.Dark.sheet : WishlistTokens.Light.photo
        second = dark ? Color(wlHex: 0x262626) : Color(wlHex: 0xE4E4E4)
    }
}
