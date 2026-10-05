package app.wishlist.android.designsystem.overlay

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.WishlistTokens

/**
 * 뒤 콘텐츠 블러(반경 12dp x 진행값). API 31 미만은 블러 없이 막만 쓴다.
 * `progress`는 draw 단계에서 읽어 매 프레임 recomposition하지 않는다.
 */
fun Modifier.wlBackdropBlur(progress: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this else graphicsLayer {
        val radius = WishlistTokens.Motion.scrimBlur.dp.toPx() * progress()
        renderEffect = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
    }

/** 어두운 막. 항상 입력을 막는다(누른 것이 아래 화면에 닿지 않는다). 막이 사라지는 동안에도 마찬가지다. */
@Composable
internal fun WLScrim(progress: () -> Float, color: Color, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress() }
            .background(color)
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    )
}
