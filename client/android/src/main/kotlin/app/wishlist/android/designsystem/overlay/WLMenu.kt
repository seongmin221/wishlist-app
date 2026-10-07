package app.wishlist.android.designsystem.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import app.wishlist.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import kotlin.math.roundToInt

private val MenuWidth = 200.dp
private val MenuGap = 8.dp
private val MenuBody = WLType.body.copy(fontSize = 15.sp)

/** ⋯ 메뉴 카드: 시트색, 모서리 20, 폭 200, 항목 높이 52, 1dp 선색 테두리, 그림자·블러 없음. */
@Composable
fun WLMenuCard(items: List<WLMenuItem>, onItemClick: (WLMenuItem) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    val shape = RoundedCornerShape(WishlistTokens.Radius.m)
    Column(
        modifier
            .width(MenuWidth)
            .background(c.sheet, shape)
            .border(1.dp, c.line, shape)
            .clip(shape)
            .padding(vertical = 6.dp),
    ) {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clickable(role = Role.Button) { onItemClick(item) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.icon?.invoke()
                WLText(item.text, MenuBody, color = c.text)
            }
        }
    }
}

/**
 * ⋯ 메뉴(OverlayHost 안). 나타남 opacity·scale(.96 -> 1) 150 ease-out, 버튼 쪽 모서리 기준. 블러·막 없음.
 * 바깥 누르기로 닫힌다(전환 중에는 무시). 항목을 누르면 메뉴가 닫히기 시작하고 항목의 `onClick`이 불린다
 * (거기서 `showDialog`를 불러도 메뉴 닫기가 끝난 뒤에 열린다).
 */
@Composable
internal fun MenuLayer(entry: MenuEntry, state: OverlayHostState) {
    val menuTitle = stringResource(R.string.wl_menu)
    val q = remember { Animatable(0f) }
    LaunchedEffect(entry.phase) {
        val spec = tween<Float>(WishlistTokens.Motion.overflowMenu, easing = WishlistTokens.Curve.easeOut)
        when (entry.phase) {
            OverlayPhase.Opening -> {
                q.animateTo(1f, spec)
                state.onOpened(entry.id)
            }
            OverlayPhase.Closing -> {
                q.animateTo(0f, spec)
                state.onClosed(entry.id)
            }
            OverlayPhase.Open -> Unit
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { state.requestDismiss(entry.id) } },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val menuW = with(density) { MenuWidth.toPx() }
        val margin = with(density) { WishlistTokens.Space.s16.toPx() }
        val anchorOnRight = entry.anchor.center.x > screenW / 2f
        val x = if (anchorOnRight) entry.anchor.right - menuW else entry.anchor.left
        val clampedX = x.coerceIn(margin, (screenW - menuW - margin).coerceAtLeast(margin))
        val y = entry.anchor.bottom + with(density) { MenuGap.toPx() }
        WLMenuCard(
            items = entry.items,
            onItemClick = { item -> if (state.requestDismiss(entry.id)) item.onClick() },
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), y.roundToInt()) }
                .semantics { paneTitle = menuTitle }
                .graphicsLayer {
                    alpha = q.value
                    val s = 0.96f + 0.04f * q.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(if (anchorOnRight) 1f else 0f, 0f)
                }
                .pointerInput(Unit) { detectTapGestures { } },
        )
    }
}
