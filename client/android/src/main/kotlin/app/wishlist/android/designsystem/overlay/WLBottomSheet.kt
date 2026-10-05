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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLOnSheet
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 시트 제목·확인창 제목 20/700(`WLType.title`에서 크기만 줄임). */
val WLSheetTitleStyle: TextStyle = WLType.title.copy(fontSize = 20.sp)

/** 시트 최대 높이(내용 높이만큼만 올라온다). 화면이 더 작으면 `sheetMaxHeight`로 줄이고 내용을 스크롤한다. */
private val SheetMaxHeight = 760.dp

/** 시트 위 끝과 상태 표시줄 사이에 항상 남기는 간격(구현 기본값, 디자인 값 아님). */
private val SheetTopMargin = WishlistTokens.Space.s24

/** 시트 높이 상한: min(760, 쓸 수 있는 높이 − 위 안전 영역 − 위 간격). 키보드가 올라오면 쓸 수 있는 높이가 줄어든다. */
internal fun sheetMaxHeight(available: Dp, topInset: Dp): Dp =
    minOf(SheetMaxHeight, (available - topInset - SheetTopMargin).coerceAtLeast(0.dp))

/** 열 때 지나치는 거리 동안 빈 곳이 보이지 않게 시트 면을 화면 아래로 더 그리는 길이(motion.md). */
private val OvershootPadding = 80.dp

/**
 * 시트 끌어내려 닫기 판정(구현 기본값): 시트 높이의 25% 이상 끌었거나 아래로 1000dp/s 이상이면 닫는다.
 * `velocity`는 **dp/s**다(속도 토큰은 dp·pt 기준). Compose `draggable`이 주는 px/s는 `sheetDragEndPx`로 바꿔 넘긴다.
 */
fun shouldDismissSheet(dragDistance: Float, sheetHeight: Float, velocity: Float): Boolean =
    dragDistance >= sheetHeight * WishlistTokens.Motion.sheetDragDismissDistanceRatio ||
        velocity >= WishlistTokens.Motion.sheetDragDismissVelocity

internal enum class SheetDragEnd { Dismiss, SnapBack, Ignore }

/**
 * 끌기가 끝났을 때의 결정(순수 함수). 이미 닫히는 중이거나 열리는 중이면(예: 끄는 중 뒤로·막 누르기로 닫기가 시작되어
 * 끌기가 취소로 끝난 경우) 아무것도 하지 않는다. 되돌림 애니메이션이 닫기 애니메이션을 끊으면 overlay가 영영 멈춘다.
 */
internal fun sheetDragEnd(phase: OverlayPhase, dragDistance: Float, sheetHeight: Float, velocity: Float): SheetDragEnd = when {
    phase != OverlayPhase.Open -> SheetDragEnd.Ignore
    shouldDismissSheet(dragDistance, sheetHeight, velocity) -> SheetDragEnd.Dismiss
    else -> SheetDragEnd.SnapBack
}

/** `draggable`의 px 단위 값(거리 px, 속도 px/s)으로 결정한다. 속도는 `density`로 나눠 dp/s로 바꾼 뒤 판정한다. */
internal fun sheetDragEndPx(phase: OverlayPhase, dragDistancePx: Float, sheetHeightPx: Float, velocityPxPerSecond: Float, density: Float): SheetDragEnd =
    sheetDragEnd(phase, dragDistancePx, sheetHeightPx, velocityPxPerSecond / density)

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
                // 다른 곳이 p를 건드려 이 애니메이션이 끊겨도(CancellationException) 이 효과가 살아 있으면 다시 시작해 반드시 끝낸다.
                while (p.value > 0f) {
                    try {
                        p.animateTo(0f, tween(WishlistTokens.Motion.sheetClose, easing = WishlistTokens.Curve.accelerate))
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                state.onClosed(entry.id)
            }
            OverlayPhase.Open -> Unit
        }
    }

    var heightPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        if (entry.phase == OverlayPhase.Open && heightPx > 0f) {
            scope.launch { p.snapTo((p.value - delta / heightPx).coerceIn(0f, 1f)) }
        }
    }
    val extra = OvershootPadding
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight(maxHeight, topInset))
                .semantics { paneTitle = "시트" }
                .graphicsLayer {
                    if (heightPx != size.height) heightPx = size.height
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
                                when (sheetDragEndPx(entry.phase, distance, heightPx, velocity, density.density)) {
                                    SheetDragEnd.Dismiss -> if (!state.requestDismiss(entry.id)) scope.launch { snapBack(p) }
                                    SheetDragEnd.SnapBack -> scope.launch { snapBack(p) }
                                    SheetDragEnd.Ignore -> Unit
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
            // 상한보다 긴 내용(큰 글자·키보드)은 시트 안에서 스크롤한다. 짧으면 내용 높이만큼만 차지한다.
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = WishlistTokens.Space.screenMargin)
                    .padding(bottom = WishlistTokens.Space.s16)
                    .navigationBarsPadding(),
            ) {
                WLOnSheet { entry.content() }
            }
        }
    }
}

private suspend fun snapBack(p: Animatable<Float, *>) {
    p.animateTo(1f, tween(WishlistTokens.Motion.sheetDragDismissSnapBack, easing = WishlistTokens.Curve.springSheet))
}
