package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 위쪽 바(내비게이션 바) 기준값(디자인 결정 2026-10-07 위쪽 바 위치 통일). 모든 하위 화면의 뒤로·⋯가 같은 자리에 오게
 * 이 값만 쓴다. 머리 시트처럼 바 위치에 맞춰 다른 요소를 배치하는 화면도 여기서 읽는다.
 */
object WLTopBarMetrics {
    /** 바 높이. */
    val Height: Dp = 56.dp

    /** 안전 영역 위 끝에서 바 윗변까지. */
    val TopGap: Dp = 6.dp

    /** 바 좌우 여백(서비스 공통 화면 여백). 왼쪽 버튼 x, 오른쪽 버튼의 오른쪽 여백. */
    val SideMargin: Dp = WishlistTokens.Space.screenMargin

    /** 안전 영역 위 끝에서 44 버튼 윗변까지(바 안 세로 가운데 = 6 + (56 − 44) / 2). */
    val ButtonTop: Dp = TopGap + (Height - WishlistTokens.Space.minTouch) / 2

    /** 안전 영역 위 끝에서 바 아래 끝까지(62). */
    val Bottom: Dp = TopGap + Height
}

/** 기기의 실제 위쪽 안전 영역: 상태 바와 화면 컷아웃 중 큰 쪽. 고정 숫자를 쓰지 않는다. */
@Composable
fun wlSafeTop(): Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()

/** 화면 위 끝에서 바 아래 끝까지(`safeTop + 62`). 머리 시트의 expanded 윗변, 바 아래 내용 시작점. */
@Composable
fun wlTopBarBottom(): Dp = wlSafeTop() + WLTopBarMetrics.Bottom

/**
 * 위쪽 바: 안전 영역 아래 6부터 높이 56, 좌우 20. leading(뒤로) · 가운데 `title`(세로 가운데) · trailing(⋯ 등, 간격 10).
 * - `background`가 없으면 버튼만 떠 있다(상품 상세처럼 스크롤 위에 떠 있는 버튼). 있으면 바와 **상태 바 뒤까지** 같은 색으로 칠한다
 *   (붙어 있는 머리 뒤로 목록이 상태 바 영역에 비치지 않게).
 * - `sideMargin`: 호출하는 쪽이 이미 좌우 여백을 준 경우(탭 첫 화면 내용 칼럼)에만 0으로 바꾼다.
 * - 큰 글자에서 가운데 내용이 56보다 크면 바가 늘어나고 버튼은 세로 가운데를 유지한다.
 */
@Composable
fun WLTopBar(
    modifier: Modifier = Modifier,
    background: Color? = null,
    sideMargin: Dp = WLTopBarMetrics.SideMargin,
    titleSpacing: Dp = WishlistTokens.Space.s12,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().then(if (background != null) Modifier.background(background) else Modifier)) {
        Spacer(Modifier.height(wlSafeTop() + WLTopBarMetrics.TopGap))
        Row(
            Modifier.fillMaxWidth().heightIn(min = WLTopBarMetrics.Height).padding(horizontal = sideMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                if (title != null) Spacer(Modifier.width(titleSpacing))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { title?.invoke() }
            if (trailing != null) {
                if (title != null) Spacer(Modifier.width(titleSpacing))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}
