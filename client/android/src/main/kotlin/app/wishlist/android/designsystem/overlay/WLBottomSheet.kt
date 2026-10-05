package app.wishlist.android.designsystem.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLOnSheet
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import kotlinx.coroutines.launch

/** 시트 제목·확인창 제목 20/700(`WLType.title`에서 크기만 줄임). */
val WLSheetTitleStyle: TextStyle = WLType.title.copy(fontSize = 20.sp)

/** 시트 최대 높이(내용 높이만큼만 올라온다). */
private val SheetMaxHeight = 760.dp

/** 열 때 지나치는 거리 동안 빈 곳이 보이지 않게 시트 면을 화면 아래로 더 그리는 길이(motion.md). */
private val OvershootPadding = 80.dp

/** 시트 끌어내려 닫기 판정(구현 기본값): 시트 높이의 25% 이상 끌었거나 아래로 1000/s 이상이면 닫는다. */
fun shouldDismissSheet(dragDistance: Float, sheetHeight: Float, velocity: Float): Boolean =
    dragDistance >= sheetHeight * WishlistTokens.Motion.sheetDragDismissDistanceRatio ||
        velocity >= WishlistTokens.Motion.sheetDragDismissVelocity

/** 시트 머리: 제목(20/700) + 닫기 44 원형 버튼. */
@Composable
fun WLSheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WLText(title, WLSheetTitleStyle, Modifier.weight(1f), color = c.text)
        Box(
            Modifier
                .size(WishlistTokens.Space.minTouch)
                .clip(CircleShape)
                .background(c.sheetField)
                .semantics { contentDescription = "닫기" }
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val stroke = c.text
            Canvas(Modifier.size(20.dp)) {
                val d = 6.dp.toPx()
                val w = 1.8.dp.toPx()
                val m = center
                drawLine(stroke, Offset(m.x - d, m.y - d), Offset(m.x + d, m.y + d), w, StrokeCap.Round)
                drawLine(stroke, Offset(m.x - d, m.y + d), Offset(m.x + d, m.y - d), w, StrokeCap.Round)
            }
        }
    }
}

/**
 * 직접 그린 바텀시트(OverlayHost 안). translateY 진행값 `p`: 0 = 화면 아래로 완전히 내려감, 1 = 제자리.
 * 열기 tween(480, spring-sheet)는 1을 살짝 넘겼다 돌아오므로 면을 80dp 아래로 더 그린다. 닫기 tween(260, accelerate).
 * 끌어내리기는 손잡이 줄에서만 받는다(손잡이는 끌 수 있는 시트에만 보인다).
 */
@Composable
internal fun SheetLayer(entry: SheetEntry, state: OverlayHostState) {
    val c = LocalWLColors.current
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entry.phase) {
        when (entry.phase) {
            OverlayPhase.Opening -> {
                p.animateTo(1f, tween(WishlistTokens.Motion.sheetOpen, easing = WishlistTokens.Curve.springSheet))
                state.onOpened(entry.id)
            }
            OverlayPhase.Closing -> {
                p.animateTo(0f, tween(WishlistTokens.Motion.sheetClose, easing = WishlistTokens.Curve.accelerate))
                state.onClosed(entry.id)
            }
            OverlayPhase.Open -> Unit
        }
    }

    var heightPx = 0f
    val dragState = rememberDraggableState { delta ->
        if (heightPx > 0f) scope.launch { p.snapTo((p.value - delta / heightPx).coerceIn(0f, 1f)) }
    }
    val extra = OvershootPadding

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = SheetMaxHeight)
                .graphicsLayer {
                    heightPx = size.height
                    translationY = (1f - p.value) * size.height
                }
                .drawBehind {
                    drawRect(
                        c.sheet,
                        Offset(0f, size.height - WishlistTokens.Radius.xl.toPx()),
                        Size(size.width, WishlistTokens.Radius.xl.toPx() + extra.toPx()),
                    )
                }
                .background(c.sheet, RoundedCornerShape(topStart = WishlistTokens.Radius.xl, topEnd = WishlistTokens.Radius.xl))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (entry.draggable) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            enabled = entry.phase == OverlayPhase.Open,
                            onDragStopped = { velocity ->
                                val distance = (1f - p.value) * heightPx
                                if (!(shouldDismissSheet(distance, heightPx, velocity) && state.requestDismiss(entry.id))) {
                                    scope.launch {
                                        p.animateTo(1f, tween(WishlistTokens.Motion.sheetDragDismissSnapBack, easing = WishlistTokens.Curve.springSheet))
                                    }
                                }
                            },
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.padding(top = 10.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.handle))
                }
            } else {
                Box(Modifier.height(WishlistTokens.Space.s20))
            }
            Box(
                Modifier
                    .padding(horizontal = WishlistTokens.Space.screenMargin)
                    .padding(bottom = WishlistTokens.Space.s16)
                    .navigationBarsPadding(),
            ) {
                WLOnSheet { entry.content() }
            }
        }
    }
}
