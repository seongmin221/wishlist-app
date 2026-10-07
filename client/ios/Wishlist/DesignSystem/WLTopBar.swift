import SwiftUI

/// 위쪽 바 배치 값(디자인 결정 2026-10-07, 명세 7). 모든 하위 화면의 뒤로·⋯ 버튼이 같은 자리에 오고 탭 첫 화면 제목도
/// 같은 바 기준을 쓴다. 기준은 기기의 실제 위쪽 안전 영역(`safeAreaInsets.top`)이다(고정 상태 바 높이를 쓰지 않는다).
/// - 바: 높이 56, `safeTop + 6` ~ `safeTop + 62`.
/// - 버튼(44 원): 바 안 세로 가운데 → 윗변 `safeTop + 12`. 좌우 화면 여백 20.
/// 머리 시트처럼 안전 영역을 무시하고 직접 배치하는 화면도 이 값을 읽는다.
enum WLTopBarMetrics {
    static let height: CGFloat = 56
    static let topGap: CGFloat = 6
    static let side: CGFloat = WishlistTokens.Space.screenMargin
    static let button: CGFloat = WishlistTokens.Space.minTouch

    /// 바 윗변(화면 위에서).
    static func top(safeTop: CGFloat) -> CGFloat { safeTop + topGap }
    /// 바 아랫변(화면 위에서). 머리 시트 expanded의 시트 윗변이다.
    static func bottom(safeTop: CGFloat) -> CGFloat { safeTop + topGap + height }
    /// 바 세로 가운데(화면 위에서).
    static func centerY(safeTop: CGFloat) -> CGFloat { safeTop + topGap + height / 2 }
    /// 버튼 윗변(화면 위에서).
    static func buttonTop(safeTop: CGFloat) -> CGFloat { centerY(safeTop: safeTop) - button / 2 }
}

/// 위쪽 바: leading(뒤로) · 가운데(제목 등, 세로 가운데) · trailing(⋯ 등). 높이 56, 위 여백 6(안전 영역 아래에서), 좌우 20.
/// - 안전 영역 안에 두면 바 윗변이 `safeTop + 6`이다. 안전 영역을 무시하는 층에서는 호출하는 쪽이 `safeTop`만큼 위를 띄운다.
/// - `background`가 있으면 바와 상태 바 뒤까지 같은 색으로 칠한다(위에 붙는 머리). 없으면 떠 있는 버튼이다.
///   스크롤 안의 붙은 머리처럼 안전 영역을 받지 못하는 곳은 호출하는 쪽이 상태 바 자리를 따로 칠한다.
struct WLTopBar<Leading: View, Center: View, Trailing: View>: View {
    var background: Color?
    /// 좌우 여백. 바깥에서 이미 화면 여백을 준 곳(탭 첫 화면 머리)은 0을 넘긴다.
    var sidePadding: CGFloat = WLTopBarMetrics.side
    /// leading과 가운데 사이(목록 머리: 뒤로와 제목 사이 12).
    var spacing: CGFloat = WishlistTokens.Space.s12
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var center: () -> Center
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: spacing) {
            leading()
            // 가운데가 비어 있어도(EmptyView) 남는 폭을 채워 trailing을 오른쪽 끝에 둔다.
            HStack(spacing: 0) {
                center()
                Spacer(minLength: 0)
            }
            trailing()
        }
        .frame(height: WLTopBarMetrics.height)
        .padding(.horizontal, sidePadding)
        .padding(.top, WLTopBarMetrics.topGap)
        .background {
            if let background { background.ignoresSafeArea(edges: .top) }
        }
    }
}
