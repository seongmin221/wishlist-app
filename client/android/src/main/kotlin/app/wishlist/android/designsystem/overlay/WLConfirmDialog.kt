package app.wishlist.android.designsystem.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButtonPair
import app.wishlist.android.designsystem.WLOnSheet
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val DialogBody = WLType.body.copy(fontSize = 15.sp, lineHeight = 1.6f.em)

/** 확인창 카드: 제목 + 글머리표 영향 + 취소(왼쪽)·확인(오른쪽, 더 넓음). 모서리 xl 36, 시트색 불투명. */
@Composable
fun WLConfirmDialogCard(spec: WLDialogSpec, onCancel: () -> Unit, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(c.sheet, RoundedCornerShape(WishlistTokens.Radius.xl))
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
    ) {
        WLOnSheet {
            WLText(spec.title, WLSheetTitleStyle, color = c.text)
            if (spec.bullets.isNotEmpty()) {
                Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    spec.bullets.forEach { b ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WLText("•", DialogBody, color = c.text)
                            WLText(b, DialogBody, Modifier.weight(1f), color = c.text)
                        }
                    }
                }
            }
            WLButtonPair(
                cancelText = spec.cancelText,
                primaryText = spec.confirmText,
                primaryKind = spec.confirmKind,
                onCancel = onCancel,
                onPrimary = onConfirm,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 가운데 확인창(OverlayHost 안). 나타남 opacity·scale(.96 -> 1) 200 fade-in, 사라짐 opacity 150 ease-in(scale 그대로).
 * 시트 위에 뜨면(`dimBelow`) 시트도 한 번 더 어둡게 한다. 취소·확인은 전환 중에는 닫기를 시작하지 못해 무시된다.
 */
@Composable
internal fun DialogLayer(entry: DialogEntry, state: OverlayHostState, dimBelow: Boolean) {
    val c = LocalWLColors.current
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.96f) }

    LaunchedEffect(entry.phase) {
        when (entry.phase) {
            OverlayPhase.Opening -> {
                coroutineScope {
                    val spec = tween<Float>(WishlistTokens.Motion.dialogIn, easing = WishlistTokens.Curve.fadeIn)
                    launch { alpha.animateTo(1f, spec) }
                    launch { scale.animateTo(1f, spec) }
                }
                state.onOpened(entry.id)
            }
            OverlayPhase.Closing -> {
                alpha.animateTo(0f, tween(WishlistTokens.Motion.dialogOut, easing = WishlistTokens.Curve.easeIn))
                state.onClosed(entry.id)
            }
            OverlayPhase.Open -> Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        if (dimBelow) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(c.scrimDim))
        Box(
            Modifier
                .safeDrawingPadding()
                .padding(horizontal = WishlistTokens.Space.s24)
                .widthIn(max = 480.dp)
                .semantics { paneTitle = entry.spec.title }
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
        ) {
            WLConfirmDialogCard(
                spec = entry.spec,
                onCancel = { state.requestDismiss(entry.id) },
                onConfirm = { state.confirm(entry.id) },
            )
        }
    }
}
