package app.wishlist.android.share

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Status
import app.wishlist.shared.submission.ShareCardKind
import kotlinx.coroutines.delay

/** 카드 상태 색(디자인 토큰 Status). */
enum class ShareCardTone { DONE, PENDING, OFFLINE, FAILED }

/** 공유 카드 한 종류의 문구·아이콘·색(FShareSaved{,Local,Offline}). INVALID·STORE_FAILED는 보드에 없어 실패 색·경고 아이콘. */
data class ShareCardContent(@param:StringRes val title: Int, @param:StringRes val line: Int, val icon: WLLineIcon, val tone: ShareCardTone) {
    companion object {
        fun of(kind: ShareCardKind): ShareCardContent = when (kind) {
            ShareCardKind.SAVED -> ShareCardContent(R.string.share_saved_title, R.string.share_saved_fetching, WLLineIcon.CheckBold, ShareCardTone.DONE)
            ShareCardKind.LOCAL -> ShareCardContent(R.string.share_local_title, R.string.share_local_line, WLLineIcon.ClockBold, ShareCardTone.PENDING)
            ShareCardKind.OFFLINE -> ShareCardContent(R.string.share_local_title, R.string.share_offline_line, WLLineIcon.CloudOff, ShareCardTone.OFFLINE)
            ShareCardKind.INVALID -> ShareCardContent(R.string.share_invalid_title, R.string.share_invalid_line, WLLineIcon.Warning, ShareCardTone.FAILED)
            ShareCardKind.DEFERRED -> ShareCardContent(R.string.share_saved_title, R.string.share_saved_open_app, WLLineIcon.CheckBold, ShareCardTone.DONE)
            ShareCardKind.STORE_FAILED -> ShareCardContent(R.string.share_failed_title, R.string.share_failed_line, WLLineIcon.Warning, ShareCardTone.FAILED)
        }
    }
}

private val LineStyle = WLType.body.copy(fontSize = 13.sp, lineHeight = 1.45f.em)

/** 저장 확인 카드: 패딩 16, 모서리 28, 카드색. 48 상태 타일(모서리 14) · 도현 20 제목 · 13/1.45 보조 줄(간격 6, 보드 FShareSaved*). 버튼 없음. */
@Composable
fun ShareCard(kind: ShareCardKind, modifier: Modifier = Modifier) {
    val content = ShareCardContent.of(kind)
    val (surface, onSurface) = content.tone.colors(LocalWLDark.current)
    WLCard(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(
            Modifier.padding(WishlistTokens.Space.s16),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WLIconTile(size = 48.dp, color = surface) { WLIcon(content.icon, size = 22.dp, color = onSurface) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WLText(stringResource(content.title), WLType.display20)
                WLText(stringResource(content.line), LineStyle, color = LocalWLColors.current.textSecondary)
            }
        }
    }
}

private fun ShareCardTone.colors(dark: Boolean): Pair<Color, Color> = when (this) {
    ShareCardTone.DONE -> if (dark) Status.Dark.doneSurface to Status.Dark.doneIcon else Status.Light.doneSurface to Status.Light.doneIcon
    ShareCardTone.PENDING -> if (dark) Status.Dark.pendingSurface to Status.Dark.pendingIcon else Status.Light.pendingSurface to Status.Light.pendingIcon
    ShareCardTone.OFFLINE -> if (dark) Status.Dark.offlineSurface to Status.Dark.offlineIcon else Status.Light.offlineSurface to Status.Light.offlineIcon
    ShareCardTone.FAILED -> if (dark) Status.Dark.failedSurface to Status.Dark.failedIcon else Status.Light.failedSurface to Status.Light.failedIcon
}

/**
 * motion.md 6: 카드가 아래에서 올라오고(translateY +140 → 0, 340 `standard`), 1500 유지, 내려간다(0 → +140, 260 `accelerate`).
 * 화면 아래 40·좌우 16(내비게이션 막대가 높으면 그 위 16). 이동 거리는 140과 "카드 + 아래 여백" 중 큰 값이라 시작·끝에 카드가
 * 보이지 않는다. [kind]가 정해진 뒤(저장이 끝난 뒤)에만 움직이고, 내려간 뒤 [onFinished].
 */
@Composable
fun ShareCardHost(kind: ShareCardKind?, onFinished: () -> Unit) {
    val density = LocalDensity.current
    val bottom = max(40.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + WishlistTokens.Space.s16)
    val hidden = remember { Animatable(1f) }
    var cardHeight by remember { mutableIntStateOf(0) }
    val distancePx = with(density) { maxOf(140.dp.toPx(), cardHeight + bottom.toPx()) }

    LaunchedEffect(kind) {
        if (kind == null) return@LaunchedEffect
        hidden.animateTo(0f, tween(SHARE_CARD_IN, easing = WishlistTokens.Curve.standard))
        delay(SHARE_CARD_HOLD)
        hidden.animateTo(1f, tween(SHARE_CARD_OUT, easing = WishlistTokens.Curve.accelerate))
        onFinished()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (kind != null) {
            ShareCard(
                kind,
                Modifier
                    .padding(start = WishlistTokens.Space.s16, end = WishlistTokens.Space.s16, bottom = bottom)
                    .widthIn(max = 480.dp)
                    .onSizeChanged { cardHeight = it.height }
                    .graphicsLayer { translationY = hidden.value * distancePx },
            )
        }
    }
}

/** motion.md 6절 공유 저장 카드 시간표(ms). */
internal const val SHARE_CARD_IN = 340
internal const val SHARE_CARD_HOLD = 1500L
internal const val SHARE_CARD_OUT = 260
