import SwiftUI

/// 시트 제목·확인창 제목 20/700(`WLTextStyle.title`에서 크기만 줄임).
let wlSheetTitleStyle = WLTextStyle.title.resized(20)

/// 시트 최대 높이(내용 높이만큼만 올라온다). 화면이 더 작으면 `sheetMaxHeight(available:)`로 줄이고 내용을 스크롤한다.
private let sheetMaxHeightCap: CGFloat = 760

/// 시트 위 끝과 상태 표시줄(위 안전 영역) 사이에 항상 남기는 간격(구현 기본값, 디자인 값 아님).
private let sheetTopMargin: CGFloat = WishlistTokens.Space.s24

/// 시트 높이 상한: min(760, 위 안전 영역 아래부터 화면 아래(키보드가 있으면 키보드 위)까지 − 위 간격).
func sheetMaxHeight(available: CGFloat) -> CGFloat {
    min(sheetMaxHeightCap, max(0, available - sheetTopMargin))
}

/// 열 때 지나치는 거리 동안 빈 곳이 보이지 않게 시트 면을 화면 아래로 더 그리는 길이(motion.md).
private let overshootPadding: CGFloat = 80

/// 시트 끌어내려 닫기 판정(구현 기본값): 시트 높이의 25% 이상 끌었거나 아래로 1000pt/s 이상이면 닫는다.
/// `velocity`는 pt/s다(속도 토큰은 dp·pt 기준, `DragGesture.velocity`가 pt/s).
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
            .accessibilityLabel(String(localized: "wl.close"))
        }
    }
}

private struct SheetContentHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct SheetHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// 직접 그린 바텀시트(OverlayHost 안). 진행값 `progress`: 0 = 화면 아래로 완전히 내려감, 1 = 제자리.
/// 열기 480 spring-sheet는 1을 살짝 넘겼다 돌아오므로 면을 80pt 아래로 더 그린다. 닫기 260 accelerate.
/// 끌어내리기는 손잡이 줄에서만 받는다(손잡이는 끌 수 있는 시트에만 보인다).
/// 애니메이션의 실제 완료를 상태 기계에 전달한다. 상태 기계는 UI 없이 테스트한다.
struct SheetLayer: View {
    let entry: OverlayEntry
    let state: OverlayHostState
    let draggable: Bool
    let content: () -> AnyView

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: CGFloat = 0
    @State private var dragOffset: CGFloat = 0
    @State private var height: CGFloat = 0
    @State private var contentHeight: CGFloat = 0

    private var handleHeight: CGFloat { draggable ? 28 : WishlistTokens.Space.s20 }

    var body: some View {
        // 바깥 outer는 모든 안전 영역을 지킨다(아래 inset = 홈 표시줄 또는 키보드). 안쪽 geo는 아래 홈 표시줄만 무시하고
        // 키보드 영역은 지킨다: 키보드가 올라오면 시트가 키보드 위로 올라간다. geo는 위 안전 영역 아래부터 화면 아래
        // (키보드가 있으면 키보드 위)까지이고 geo의 아래 inset은 키보드 높이(없으면 0)다. 둘의 차이가 시트 안에 남길
        // 홈 표시줄 높이다(키보드가 있으면 0).
        GeometryReader { outer in
        GeometryReader { geo in
            let homeInset = max(0, outer.safeAreaInsets.bottom - geo.safeAreaInsets.bottom)
            let maxContent = max(0, sheetMaxHeight(available: geo.size.height) - handleHeight)
            VStack(spacing: 0) {
                handleRow
                // 내용이 상한보다 길면(큰 글자·키보드) 시트 안에서 스크롤한다. 짧으면 내용 높이만큼만 차지한다.
                ScrollView {
                    content()
                        .wlOnSheet()
                        .padding(.horizontal, WishlistTokens.Space.screenMargin)
                        .padding(.bottom, WishlistTokens.Space.s16 + homeInset)
                        .background(GeometryReader { Color.clear.preference(key: SheetContentHeightKey.self, value: $0.size.height) })
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: min(contentHeight, maxContent))
            }
            .onPreferenceChange(SheetContentHeightKey.self) { contentHeight = $0 }
            .frame(maxWidth: .infinity)
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
        }
        .ignoresSafeArea(.container, edges: .bottom)
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
        withAnimation(reduceMotion ? nil : WishlistTokens.Curve.springSheet.animation(ms: WishlistTokens.Motion.sheetDragDismissSnapBack)) { dragOffset = 0 }
    }

    private func run(_ phase: OverlayPhase) async {
        switch phase {
        case .opening:
            // 높이가 재어지기 전에 움직이면 첫 프레임이 튄다(재어질 때까지 한 프레임씩 기다린다. 뷰가 사라지면 취소).
            while (height == 0 || contentHeight == 0) && !Task.isCancelled { try? await Task.sleep(for: .milliseconds(16)) }
            if Task.isCancelled { return }
            await overlayAnimate(reduceMotion ? nil : WishlistTokens.Curve.springSheet.animation(ms: WishlistTokens.Motion.sheetOpen)) { progress = 1 }
            if !Task.isCancelled { state.onOpened(entry.id) }
        case .closing:
            await overlayAnimate(reduceMotion ? nil : WishlistTokens.Curve.accelerate.animation(ms: WishlistTokens.Motion.sheetClose)) {
                progress = 0
                dragOffset = 0
            }
            if !Task.isCancelled { state.onClosed(entry.id) }
        case .open:
            break
        }
    }
}
