import SwiftUI

/// 시트 제목·확인창 제목 20/700(`WLTextStyle.title`에서 크기만 줄임).
let wlSheetTitleStyle = WLTextStyle.title.resized(20)

/// 시트 최대 높이(내용 높이만큼만 올라온다).
private let sheetMaxHeight: CGFloat = 760

/// 열 때 지나치는 거리 동안 빈 곳이 보이지 않게 시트 면을 화면 아래로 더 그리는 길이(motion.md).
private let overshootPadding: CGFloat = 80

/// 시트 끌어내려 닫기 판정(구현 기본값): 시트 높이의 25% 이상 끌었거나 아래로 1000/s 이상이면 닫는다.
func shouldDismissSheet(dragDistance: CGFloat, sheetHeight: CGFloat, velocity: CGFloat) -> Bool {
    dragDistance >= sheetHeight * CGFloat(WishlistTokens.Motion.sheetDragDismissDistanceRatio)
        || velocity >= CGFloat(WishlistTokens.Motion.sheetDragDismissVelocity)
}

enum SheetDragEnd { case dismiss, snapBack, ignore }

/// 끌기가 끝났을 때의 결정(순수 함수). 이미 닫히는 중이거나 열리는 중이면(예: 끄는 중 뒤로·막 누르기로 닫기가 시작되어
/// 끌기가 취소로 끝난 경우) 아무것도 하지 않는다. 되돌림 애니메이션이 닫기 애니메이션을 끊으면 overlay가 영영 멈춘다.
func sheetDragEnd(phase: OverlayPhase, dragDistance: CGFloat, sheetHeight: CGFloat, velocity: CGFloat) -> SheetDragEnd {
    if phase != .open { return .ignore }
    return shouldDismissSheet(dragDistance: dragDistance, sheetHeight: sheetHeight, velocity: velocity) ? .dismiss : .snapBack
}

/// 시트 머리: 제목(20/700) + 닫기 44 원형 버튼.
struct WLSheetHeader: View {
    let title: String
    let onClose: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: 8) {
            WLText(title, wlSheetTitleStyle, color: c.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            Button(action: onClose) {
                Path { p in
                    p.move(to: CGPoint(x: 4, y: 4)); p.addLine(to: CGPoint(x: 16, y: 16))
                    p.move(to: CGPoint(x: 4, y: 16)); p.addLine(to: CGPoint(x: 16, y: 4))
                }
                .stroke(c.text, style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
                .frame(width: 20, height: 20)
                .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                .background(c.sheetField, in: Circle())
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("닫기")
        }
    }
}

private struct SheetHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// 직접 그린 바텀시트(OverlayHost 안). 진행값 `progress`: 0 = 화면 아래로 완전히 내려감, 1 = 제자리.
/// 열기 480 spring-sheet는 1을 살짝 넘겼다 돌아오므로 면을 80pt 아래로 더 그린다. 닫기 260 accelerate.
/// 끌어내리기는 손잡이 줄에서만 받는다(손잡이는 끌 수 있는 시트에만 보인다).
/// 애니메이션 완료는 토큰 시간만큼의 대기로 알리고(`.task(id: phase)`, 뷰가 사라질 때만 취소) 상태 기계는 UI 없이 테스트한다.
struct SheetLayer: View {
    let entry: OverlayEntry
    let state: OverlayHostState
    let draggable: Bool
    let content: () -> AnyView

    @Environment(\.wlColors) private var c
    @State private var progress: CGFloat = 0
    @State private var dragOffset: CGFloat = 0
    @State private var height: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                handleRow
                content()
                    .wlOnSheet()
                    .padding(.horizontal, WishlistTokens.Space.screenMargin)
                    .padding(.bottom, WishlistTokens.Space.s16 + geo.safeAreaInsets.bottom)
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: sheetMaxHeight, alignment: .top)
            .fixedSize(horizontal: false, vertical: true)
            .background(alignment: .bottom) {
                // 면: 위 모서리 36 + 열 때 지나치는 동안 보이는 아래쪽 80pt.
                ZStack(alignment: .bottom) {
                    UnevenRoundedRectangle(topLeadingRadius: WishlistTokens.Radius.xl, topTrailingRadius: WishlistTokens.Radius.xl, style: .continuous)
                        .fill(c.sheet)
                    Rectangle().fill(c.sheet)
                        .frame(height: WishlistTokens.Radius.xl + overshootPadding)
                        .offset(y: overshootPadding)
                }
                .contentShape(Rectangle())
                .onTapGesture {}
            }
            .background(GeometryReader { Color.clear.preference(key: SheetHeightKey.self, value: $0.size.height) })
            .onPreferenceChange(SheetHeightKey.self) { height = $0 }
            .offset(y: (1 - progress) * height + dragOffset)
            .opacity(height > 0 ? 1 : 0)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .ignoresSafeArea(.all, edges: .bottom)
        }
        .task(id: entry.phase) { await run(entry.phase) }
    }

    @ViewBuilder private var handleRow: some View {
        if draggable {
            Capsule().fill(c.handle)
                .frame(width: 40, height: 5)
                .padding(.top, 10)
                .frame(maxWidth: .infinity, minHeight: 28, alignment: .top)
                .contentShape(Rectangle())
                .gesture(drag)
                .accessibilityHidden(true)
        } else {
            Color.clear.frame(height: WishlistTokens.Space.s20)
        }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { v in
                guard entry.phase == .open, height > 0 else { return }
                dragOffset = min(max(0, v.translation.height), height)
            }
            .onEnded { v in
                switch sheetDragEnd(phase: entry.phase, dragDistance: dragOffset, sheetHeight: height, velocity: v.velocity.height) {
                case .dismiss: if !state.requestDismiss(entry.id) { snapBack() }
                case .snapBack: snapBack()
                case .ignore: break
                }
            }
    }

    private func snapBack() {
        withAnimation(WishlistTokens.Curve.springSheet.animation(ms: WishlistTokens.Motion.sheetDragDismissSnapBack)) { dragOffset = 0 }
    }

    private func run(_ phase: OverlayPhase) async {
        switch phase {
        case .opening:
            // 높이가 재어지기 전에 움직이면 첫 프레임이 튄다. 한 프레임씩 최대 30번 기다린다.
            var tries = 0
            while height == 0 && tries < 30 {
                await overlaySleep(ms: 16)
                tries += 1
            }
            withAnimation(WishlistTokens.Curve.springSheet.animation(ms: WishlistTokens.Motion.sheetOpen)) { progress = 1 }
            await overlaySleep(ms: WishlistTokens.Motion.sheetOpen)
            if !Task.isCancelled { state.onOpened(entry.id) }
        case .closing:
            withAnimation(WishlistTokens.Curve.accelerate.animation(ms: WishlistTokens.Motion.sheetClose)) {
                progress = 0
                dragOffset = 0
            }
            await overlaySleep(ms: WishlistTokens.Motion.sheetClose)
            if !Task.isCancelled { state.onClosed(entry.id) }
        case .open:
            break
        }
    }
}
