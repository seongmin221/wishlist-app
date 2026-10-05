package app.wishlist.android.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 펼치기 화살표(아래 방향 V). 펼치면 180도 뒤집힌다(200 ease). */
@Composable
fun WLChevron(expanded: Boolean, modifier: Modifier = Modifier, color: Color = LocalWLColors.current.text) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(WishlistTokens.Motion.disclosureArrow, easing = WishlistTokens.Curve.ease),
        label = "chevron",
    )
    Canvas(modifier.size(20.dp).rotate(angle)) {
        val w = 1.8.dp.toPx()
        val cx = size.width / 2
        val cy = size.height / 2
        val d = 4.5.dp.toPx()
        val stroke = Stroke(w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - d, cy - d / 2), Offset(cx, cy + d / 2), w, StrokeCap.Round)
        drawLine(color, Offset(cx, cy + d / 2), Offset(cx + d, cy - d / 2), w, StrokeCap.Round)
    }
}

/**
 * 접히는 묶음 면(머리 줄 + 펼침 내용). 펼침 내용은 높이·opacity 200 `ease`.
 * 머리 줄 전체가 접기·펼치기 버튼이다(확인창 안 상품 목록). 홈 할 일 카드처럼 화살표만 따로 누르려면 `WLChevron`을 직접 쓴다.
 */
@Composable
fun ExpandableGroup(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    WLCard(modifier.fillMaxWidth(), radius = WishlistTokens.Radius.m) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = WishlistTokens.Space.minTouch)
                    .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                header()
                WLChevron(expanded)
            }
            val spec = tween<Float>(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeIn(spec),
                exit = shrinkVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeOut(spec),
            ) { Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { content() } }
        }
    }
}
