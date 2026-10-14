import SwiftUI

private let dialogBody = WLTextStyle.body.resized(15, lineHeight: 15 * 1.6)
private let dialogTarget = WLTextStyle.bodyBold.resized(15)

/// 확인창 카드: (머리 타일) + 제목 + 글머리표 영향 + 취소(왼쪽)·확인(오른쪽, 더 넓음). 모서리 xl 36, 시트색 불투명.
struct WLConfirmDialogCard: View {
    let spec: WLDialogSpec
    let onCancel: () -> Void
    let onConfirm: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            spec.icon
            WLText(spec.title, wlSheetTitleStyle, color: c.text)
                .accessibilityAddTraits(.isHeader)
            if let target = spec.target {
                HStack(spacing: WishlistTokens.Space.s12) {
                    target.thumbnail
                    WLText(target.text, dialogTarget, color: c.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(10)
                .background(c.sheetField, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
                .accessibilityElement(children: .combine)
            }
            if !spec.bullets.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(spec.bullets.enumerated()), id: \.offset) { _, b in
                        // 글머리표와 글은 같은 스타일(같은 줄 상자)이라 .top이면 첫 줄에 맞는다(여러 줄 WLText에는 글자 기준선이 없다).
                        HStack(alignment: .top, spacing: 8) {
                            WLText("•", dialogBody, color: c.text).accessibilityHidden(true)
                            WLText(b, dialogBody, color: c.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .padding(.leading, 4)
            }
            WLButtonPair(cancelText: spec.cancelText, primaryText: spec.confirmText, primaryKind: spec.confirmKind,
                         onCancel: onCancel, onPrimary: onConfirm)
                .padding(.top, 4)
        }
        .wlOnSheet()
        .padding(.init(top: 24, leading: 20, bottom: 20, trailing: 20))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.sheet, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.xl, style: .continuous))
    }
}

/// 가운데 확인창(OverlayHost 안). 나타남 opacity·scale(.96 -> 1) 200 fade-in, 사라짐 opacity 150 ease-in(scale 그대로).
/// 시트 위에 뜨면(`dimBelow`) 시트도 한 번 더 어둡게 한다. 취소·확인은 전환 중에는 닫기를 시작하지 못해 무시된다.
struct DialogLayer: View {
    let entry: OverlayEntry
    let state: OverlayHostState
    let spec: WLDialogSpec
    let dimBelow: Bool

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var alpha = 0.0
    @State private var scale = 0.96

    var body: some View {
        ZStack {
            Color.clear.contentShape(Rectangle()).onTapGesture {}.ignoresSafeArea()
            if dimBelow { c.scrimDim.opacity(alpha).ignoresSafeArea().allowsHitTesting(false) }
            WLConfirmDialogCard(spec: spec,
                                onCancel: { state.requestDismiss(entry.id) },
                                onConfirm: { state.confirm(entry.id) })
                .frame(maxWidth: 480)
                .padding(.horizontal, WishlistTokens.Space.s24)
                .scaleEffect(reduceMotion ? 1 : scale)
                .opacity(alpha)
        }
        .task(id: entry.phase) { await run(entry.phase) }
    }

    private func run(_ phase: OverlayPhase) async {
        switch phase {
        case .opening:
            await overlayAnimate(reduceMotion ? nil : WishlistTokens.Curve.fadeIn.animation(ms: WishlistTokens.Motion.dialogIn)) {
                alpha = 1
                scale = 1
            }
            if !Task.isCancelled { state.onOpened(entry.id) }
        case .closing:
            await overlayAnimate(reduceMotion ? nil : WishlistTokens.Curve.easeIn.animation(ms: WishlistTokens.Motion.dialogOut)) { alpha = 0 }
            if !Task.isCancelled { state.onClosed(entry.id) }
        case .open:
            break
        }
    }
}
