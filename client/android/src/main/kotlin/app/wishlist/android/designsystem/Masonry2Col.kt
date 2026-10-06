package app.wishlist.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 2열 엇갈림(masonry) 배치. 자식을 차례로 더 짧은 열에 쌓아 사진 비율을 살린다(격자에 가두지 않는다).
 * 스크롤은 호출하는 쪽 책임(세로 스크롤 컨테이너 안에 두거나 `verticalScroll`을 붙인다). 자식 수가 수십 개인 목록용이다.
 */
@Composable
fun Masonry2Col(
    modifier: Modifier = Modifier,
    gap: Dp = WishlistTokens.Space.s12,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        require(constraints.hasBoundedWidth) { "Masonry2Col requires a finite width; constrain it with width() in horizontal scrolling containers." }
        val gapPx = gap.roundToPx()
        val colWidth = ((constraints.maxWidth - gapPx) / 2).coerceAtLeast(0)
        val childConstraints = constraints.copy(minWidth = colWidth, maxWidth = colWidth, minHeight = 0)
        val heights = intArrayOf(0, 0)
        val placed = measurables.map { m ->
            val p = m.measure(childConstraints)
            val col = if (heights[0] <= heights[1]) 0 else 1
            val y = heights[col]
            heights[col] += p.height + gapPx
            Triple(p, col, y)
        }
        val h = (maxOf(heights[0], heights[1]) - gapPx).coerceAtLeast(0)
        layout(constraints.maxWidth, constraints.constrainHeight(h)) {
            placed.forEach { (p, col, y) -> p.place(col * (colWidth + gapPx), y) }
        }
    }
}
