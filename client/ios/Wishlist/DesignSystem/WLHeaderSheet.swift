import Observation
import SwiftUI

// 머리 위 바텀시트(FPurposeDetailL 후보 목록, 아카이브 상세도 같은 구조). 디자인 결정 2026-10-07(명세 5A).
// 이름은 Android와 같다: `WLHeaderSheet`, `WLHeaderSheetState`, `WLSheetDetent`, `progress`·`detent`·`animate(to:)`·`toggle()`.
//
// 구조: 뒤에는 고정된 머리, 앞에는 머리 위로 오르내리는 시트(흰 `sheet` 면, 위 모서리 36, 손잡이 줄 28).
// 멈추는 높이는 둘뿐이다(시트 윗변 기준).
// - `resting`: 머리 내용의 실제 측정 높이 바로 아래. 머리가 길어지면(편집) 애니메이션으로 따라 내려간다. 끌어서는
//   resting 아래로 갈 수 없고 고무줄처럼만 늘어난다(편집 자리는 프로그램으로만 간다).
// - `expanded`: 호출하는 쪽이 주는 위쪽 바 바로 아래(`expandedTop`).
//
// 구현
// - 시트는 `ScrollView` 하나다. 시트 위에 resting 높이만큼 빈 자리를 두면 스크롤 0 = resting, 스크롤 `travel`
//   (= resting − expanded) = expanded이고 그 뒤는 목록 스크롤이다. 그래서 지도 앱 시트처럼 한 번의 끌기 안에서
//   "시트가 먼저 올라가고 expanded에 닿으면 목록이 스크롤"되고, 목록이 맨 위일 때 아래로 끌면 시트가 내려간다.
//   resting 아래는 스크롤 뷰의 위 바운스(고무줄)다.
// - 머리는 스크롤하지 않는 층으로 스크롤 뷰 위에 둔다. 이 층이 시트 윗변 위 영역(0 ~ 시트 윗변)의 눌림을 모두
//   가져가므로 머리 빈 곳을 끌어도 시트·목록이 움직이지 않고 머리 버튼은 눌린다. 시트 윗변 아래 눌림만 스크롤 뷰가 받는다.
//   - `header`(설명·버튼처럼 시트에 덮이는 부분, 높이 = resting)는 시트 윗변에서 잘라 시트 뒤에 있는 것처럼 보이게 한다.
//   - `bar`(위쪽 바와 진행값에 따라 위쪽 바로 올라가는 제목)는 그대로 그린다(시트 윗변보다 위에만 있어야 한다).
//   - 위쪽 바 자리(0 ~ expandedTop)는 `barBackground` 면이 덮어 expanded에서 스크롤되어 올라온 목록을 가린다.
// - expanded를 지나 목록이 스크롤되면 시트 윗변 띠(모서리 36 + 손잡이)가 expandedTop에 붙어 남아 시트는 멈추고
//   그 안의 목록만 스크롤되는 것처럼 보인다.
// - 놓으면 `ScrollTargetBehavior`가 0 < 목표 < travel인 자리를 속도 방향(속도가 없으면 마지막 스크롤 방향)의
//   멈춤 높이로 바꾼다. 남은 플링은 expanded를 지나 목록 스크롤로 이어진다. 놓은 뒤 스냅 곡선은 시스템 감속이다.
// - 손잡이 탭·`animate(to:)`는 300 `emphasized`로 스크롤한다(Reduce Motion이면 바로).
// - 왼쪽 가장자리 뒤로 끌기는 window 인식기가 스크롤보다 먼저다(`WLEdgeBackGesture`).

/// 시트가 멈추는 높이.
enum WLSheetDetent: Equatable { case resting, expanded }

/// 시트 상태와 제어. 화면이 하나 들고(`@State`) `WLHeaderSheet`에 넘긴다.
@Observable
final class WLHeaderSheetState {
    struct Request: Equatable {
        let detent: WLSheetDetent
        let serial: Int
    }

    /// 0 = resting, 1 = expanded. 손가락을 따라 연속으로 바뀐다(머리 변형에 쓴다).
    fileprivate(set) var progress: CGFloat = 0
    /// resting의 시트 윗변(화면 위에서 pt) = 머리 실제 높이. 머리 높이가 바뀌면 애니메이션으로 따라간다.
    fileprivate(set) var restingTop: CGFloat = 0
    /// 지금 시트 윗변(화면 위에서 pt). 머리 층이 눌림을 받는 영역과 `header`를 자르는 높이다.
    fileprivate(set) var sheetTop: CGFloat = 0
    /// 처리할 이동 요청(`WLHeaderSheet`가 읽어 스크롤한다).
    fileprivate(set) var request: Request?
    /// expanded에서 목록이 스크롤된 상태(시트 윗변 띠의 손잡이가 눌림을 받는다).
    fileprivate(set) var isListScrolled = false

    @ObservationIgnored fileprivate var expandedTop: CGFloat = 0
    /// 마지막 스크롤 방향(true = 위로 밀어 펼치는 쪽). 속도 없이 놓았을 때 스냅 방향이다.
    @ObservationIgnored fileprivate var lastExpanding = true
    @ObservationIgnored fileprivate var lastOffset: CGFloat = 0
    @ObservationIgnored private var serial = 0

    init() {}

    /// 가까운 멈춤 높이.
    var detent: WLSheetDetent { progress >= 0.5 ? .expanded : .resting }

    /// resting ↔ expanded 이동 거리.
    var travel: CGFloat { max(0, restingTop - expandedTop) }

    /// 프로그램으로 멈춤 높이로 보낸다(예: 펼친 상태에서 편집을 시작하면 먼저 `.resting`).
    func animate(to detent: WLSheetDetent) {
        serial += 1
        request = Request(detent: detent, serial: serial)
    }

    /// 손잡이 탭: resting ↔ expanded.
    func toggle() { animate(to: detent == .expanded ? .resting : .expanded) }
}

/// 시트 위치 계산(단위 테스트 대상).
enum WLHeaderSheetMath {
    /// 스크롤 위치 → 진행값(0~1).
    static func progress(offset: CGFloat, travel: CGFloat) -> CGFloat {
        guard travel > 0 else { return 0 }
        return min(1, max(0, offset / travel))
    }

    /// 스크롤 위치 → 화면 위에서 시트 윗변. resting 아래(고무줄)는 따라 내려가고 expanded 위로는 가지 않는다.
    static func sheetTop(offset: CGFloat, restingTop: CGFloat, expandedTop: CGFloat) -> CGFloat {
        max(min(restingTop, expandedTop), restingTop - offset)
    }

    /// 놓은 뒤 멈출 자리 `y`가 두 멈춤 높이 사이(0 < y < travel)면 바꿀 자리. 아니면 nil(그대로: 목록 스크롤·고무줄).
    /// `velocity` > 0은 위로 밀어 펼치는 쪽. 속도가 없으면 `expanding`(마지막 스크롤 방향)을 따른다.
    static func snapTarget(y: CGFloat, travel: CGFloat, velocity: CGFloat, expanding: Bool) -> CGFloat? {
        guard travel > 0, y > 0.5, y < travel - 0.5 else { return nil }
        let up = velocity > 0 ? true : (velocity < 0 ? false : expanding)
        return up ? travel : 0
    }
}

struct WLHeaderSheet<Header: View, Bar: View, Content: View>: View {
    let state: WLHeaderSheetState
    /// expanded의 시트 윗변(화면 위에서 pt). 위쪽 바 아래 끝(`WLTopBarMetrics.bottom(safeTop:)` = safeTop + 62).
    let expandedTop: CGFloat
    /// 시트 윗변 위로 보이는 면의 색(목적 색). 화면 바탕도 같은 색이어야 한다.
    let barBackground: Color
    /// 머리 높이가 바뀌었을 때 resting이 따라가는 모션(편집 시작 260 `ease`).
    var restingAnimation: Animation = WishlistTokens.Curve.ease.animation(ms: WLHeaderSheetMotion.restingMillis)
    /// 시트 내용 아래 여백(보드 140, 탭 바 여백 포함).
    var bottomPadding: CGFloat = 140
    /// 시트 뒤 머리(진행값 0 = resting, 1 = expanded). 위 여백(상태 바 포함)부터 아래 여백까지의 높이가 resting이다.
    @ViewBuilder let header: (CGFloat) -> Header
    /// 시트 앞 머리(위쪽 바 버튼, 위로 올라가는 제목). `header`와 같은 위치에서 시작하고 시트 윗변보다 위에만 그린다.
    @ViewBuilder let bar: (CGFloat) -> Bar
    /// 손잡이 줄 아래 시트 내용.
    @ViewBuilder let content: () -> Content

    @Environment(\.wlColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let space = "wl-header-sheet"
    private let restingID = "wl-header-sheet-resting"
    private let expandedID = "wl-header-sheet-expanded"

    var body: some View {
        GeometryReader { proxy in
            let screenHeight = proxy.size.height + proxy.safeAreaInsets.top + proxy.safeAreaInsets.bottom
            ScrollViewReader { scroll in
                ZStack(alignment: .top) {
                    sheetScroll(screenHeight: screenHeight)
                    headerLayer(width: proxy.size.width, scroll: scroll)
                }
                .onChange(of: state.request) { _, request in
                    guard let request else { return }
                    go(to: request.detent, scroll)
                }
            }
            .onAppear { state.expandedTop = expandedTop }
            .onChange(of: expandedTop) { _, value in state.expandedTop = value }
        }
    }

    private func sheetScroll(screenHeight: CGFloat) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                // resting 자리(높이 = resting). 위 칸 위끝을 화면 맨 위로 스크롤하면 resting, 아래 칸(높이 expandedTop)
                // 위끝을 화면 맨 위로 스크롤하면 시트 윗변 = expandedTop이다. 이 자리의 눌림은 머리 층이 가져간다.
                Color.clear.frame(height: state.travel).id(restingID)
                Color.clear.frame(height: state.restingTop - state.travel).id(expandedID)
                sheet(minHeight: max(0, screenHeight - expandedTop))
            }
            .onGeometryChange(for: CGFloat.self) { -$0.frame(in: .named(space)).minY } action: { offset in
                track(offset)
            }
        }
        .coordinateSpace(name: space)
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(WLHeaderSheetSnap(state: state))
        .ignoresSafeArea(.container, edges: [.top, .bottom])
    }

    /// 스크롤하지 않는 머리 층. 시트 윗변 위 영역의 눌림을 모두 받는다(빈 곳은 아무 동작 없이 삼킨다).
    private func headerLayer(width: CGFloat, scroll: ScrollViewProxy) -> some View {
        ZStack(alignment: .top) {
            WLHeaderSheetHost(state: state, view: header)
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                    updateResting(height, scroll)
                }
                // 시트 윗변에서 잘라 시트 뒤에 있는 것처럼 보이게 한다.
                .mask(alignment: .top) { WLHeaderSheetClip(state: state) }
            // 위쪽 바 자리: expanded에서 스크롤되어 올라온 목록을 가린다.
            barBackground
                .frame(height: expandedTop)
                .accessibilityHidden(true)
            WLHeaderSheetHost(state: state, view: bar)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .contentShape(WLHeaderSheetHitShape(height: state.sheetTop))
        .accessibilitySortPriority(1)
        .ignoresSafeArea(.container, edges: [.top, .bottom])
    }

    private func sheet(minHeight: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s12) {
            WLHeaderSheetHandle(state: state)
            content()
        }
        .padding(.horizontal, WishlistTokens.Space.s16)
        .padding(.bottom, bottomPadding)
        .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .top)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: WishlistTokens.Radius.xl, topTrailingRadius: WishlistTokens.Radius.xl,
                                   style: .continuous)
                .fill(c.sheet)
                // 아래 끝 바운스에도 머리 색이 비치지 않게 화면 높이만큼 더 그린다.
                .padding(.bottom, -minHeight)
        }
        .overlay(alignment: .top) {
            WLHeaderSheetTopBand(state: state, barBackground: barBackground, expandedTop: expandedTop)
        }
    }

    private func track(_ offset: CGFloat) {
        if abs(offset - state.lastOffset) > 0.5 {
            state.lastExpanding = offset > state.lastOffset
            state.lastOffset = offset
        }
        let p = WLHeaderSheetMath.progress(offset: offset, travel: state.travel)
        if p != state.progress { state.progress = p }
        let top = WLHeaderSheetMath.sheetTop(offset: offset, restingTop: state.restingTop, expandedTop: state.expandedTop)
        if top != state.sheetTop { state.sheetTop = top }
        let scrolled = state.travel > 0 && offset > state.travel + 1
        if scrolled != state.isListScrolled { state.isListScrolled = scrolled }
    }

    private func updateResting(_ height: CGFloat, _ scroll: ScrollViewProxy) {
        guard abs(height - state.restingTop) > 0.5 else { return }
        if state.restingTop == 0 {
            state.restingTop = height
            state.sheetTop = height
            return
        }
        // 시트가 resting에 있었다면 빈 자리 높이와 함께 내려간다. 펼쳐 있었다면 expanded를 지킨다
        // (편집 시작은 `animate(to: .resting)` 뒤에 머리를 늘리는 순서를 권한다).
        let wasExpanded = state.progress > 0.01
        withAnimation(reduceMotion ? nil : restingAnimation) { state.restingTop = height }
        if wasExpanded {
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) { scroll.scrollTo(expandedID, anchor: .top) }
        }
    }

    private func go(to detent: WLSheetDetent, _ scroll: ScrollViewProxy) {
        let id = detent == .expanded ? expandedID : restingID
        withAnimation(reduceMotion ? nil : WishlistTokens.Curve.emphasized.animation(ms: WLHeaderSheetMotion.snapMillis)) {
            scroll.scrollTo(id, anchor: .top)
        }
    }
}

/// 시트 모션 값(motion.md 구현 기본값: 스냅 300 `emphasized`, 편집으로 resting 이동 260 `ease`).
enum WLHeaderSheetMotion {
    static let snapMillis = WishlistTokens.Motion.headerCollapseScroll
    static let restingMillis = 260
}

/// 머리 층의 눌림 영역: 위에서 시트 윗변까지.
private struct WLHeaderSheetHitShape: Shape {
    var height: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: max(0, min(rect.height, height))))
    }
}

/// `header`를 시트 윗변에서 자르는 면. 진행값과 함께 바뀌는 쪽을 여기로 좁힌다.
private struct WLHeaderSheetClip: View {
    let state: WLHeaderSheetState

    var body: some View {
        Rectangle().frame(height: max(0, state.sheetTop))
    }
}

/// 시트 윗변 띠(모서리 36 바깥은 `barBackground`, 안은 시트 면 + 손잡이). 시트가 expandedTop보다 위로 스크롤되면
/// expandedTop에 붙어 남아 목록이 그 아래로 스크롤되는 것처럼 보이게 한다. 평소에는 실제 시트 윗변과 겹쳐 같은
/// 모습이고 눌림은 아래 실제 손잡이로 지나간다(목록이 스크롤된 동안만 이 띠의 손잡이가 눌린다).
private struct WLHeaderSheetTopBand: View {
    let state: WLHeaderSheetState
    let barBackground: Color
    let expandedTop: CGFloat

    @Environment(\.wlColors) private var c

    var body: some View {
        let shape = UnevenRoundedRectangle(topLeadingRadius: WishlistTokens.Radius.xl, topTrailingRadius: WishlistTokens.Radius.xl,
                                           style: .continuous)
        ZStack(alignment: .top) {
            barBackground.allowsHitTesting(false)
            shape.fill(c.sheet).allowsHitTesting(false)
            Button { state.toggle() } label: {
                Capsule().fill(c.handle).frame(width: 40, height: 5)
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .allowsHitTesting(state.isListScrolled)
        }
        .frame(height: WishlistTokens.Radius.xl)
        .accessibilityHidden(true)
        .visualEffect { [expandedTop] view, geometry in
            view.offset(y: max(0, expandedTop - geometry.frame(in: .scrollView).minY))
        }
    }
}

/// 진행값을 읽는 쪽을 머리로 좁힌다(스크롤마다 시트 내용을 다시 그리지 않게).
private struct WLHeaderSheetHost<V: View>: View {
    let state: WLHeaderSheetState
    let view: (CGFloat) -> V

    var body: some View { view(state.progress) }
}

/// 손잡이 줄(높이 28, 40×5 `handle`). 누르면 resting ↔ expanded.
private struct WLHeaderSheetHandle: View {
    let state: WLHeaderSheetState

    @Environment(\.wlColors) private var c

    var body: some View {
        Button { state.toggle() } label: {
            Capsule().fill(c.handle).frame(width: 40, height: 5)
                .frame(maxWidth: .infinity, minHeight: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(state.detent == .expanded
                            ? String(localized: "wl.headerSheet.collapse") : String(localized: "wl.headerSheet.expand"))
    }
}

private struct WLHeaderSheetSnap: ScrollTargetBehavior {
    let state: WLHeaderSheetState

    func updateTarget(_ target: inout ScrollTarget, context: TargetContext) {
        if let y = WLHeaderSheetMath.snapTarget(y: target.rect.minY, travel: state.travel,
                                               velocity: context.velocity.dy, expanding: state.lastExpanding) {
            target.rect.origin.y = y
        }
    }
}
