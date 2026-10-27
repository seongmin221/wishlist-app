package app.wishlist.android.feature.flow

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.DISABLED_ALPHA
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.home.ResText
import app.wishlist.android.feature.home.resolve
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.ValueSource

/** Flow wording (pure; the Presenter only counts). */
object HomeFlowText {
    /** The header counter `n / 전체`. */
    fun position(position: Int, total: Int): ResText = ResText(R.string.flow_position, args = listOf(position, total))

    /** Review summary: only this session's temporary passes (C7 saves no 확정·보류). */
    fun passedSummary(passed: Int): ResText = ResText(R.string.flow_review_summary, passed)

    /** Fill summary: only 건너뜀 (저장하고 다음 is C8). */
    fun skippedSummary(skipped: Int): ResText = ResText(R.string.flow_fill_summary, skipped)

    /** The review card's last line, from who set the category and purpose (Ruling 7, same as iOS); null: no AI line. */
    @StringRes
    fun aiLine(category: ValueSource?, purpose: ValueSource): Int? = when {
        category == ValueSource.AI && purpose == ValueSource.AI -> R.string.flow_ai_both
        category == ValueSource.AI -> R.string.flow_ai_category
        purpose == ValueSource.AI -> R.string.flow_ai_purpose
        else -> null
    }

    /** The fill header's reason, by what the server asks for. */
    @StringRes
    fun fillReason(action: RequiredAction, nameMissing: Boolean, analysis: AnalysisStatus): Int = when (action) {
        RequiredAction.CATEGORY_ASSIGNMENT -> R.string.flow_fill_reason_category
        RequiredAction.CATEGORY_REASSIGNMENT -> R.string.flow_fill_reason_category_deleted
        else -> if (nameMissing && analysis != AnalysisStatus.FAILED_RETRYABLE && analysis != AnalysisStatus.FAILED_TERMINAL) {
            R.string.flow_fill_reason_name
        } else {
            R.string.flow_fill_reason_info
        }
    }
}

/** 보드 머리: 안전 영역 아래, 닫기 원형 버튼 · 가운데 16/700 제목 · 오른쪽 44 폭 `n / 전체`(14/500, 끝나면 빈 칸). */
@Composable
internal fun FlowHeader(title: String, position: ResText?) {
    val nav = LocalWLNavigator.current
    WLTopBar(
        sideMargin = WishlistTokens.Space.s16,
        titleSpacing = WishlistTokens.Space.s8,
        leading = { WLCircleButton(WLLineIcon.Close, stringResource(R.string.flow_close), onClick = { nav.pop() }) },
        trailing = {
            Box(Modifier.width(WishlistTokens.Space.minTouch), contentAlignment = Alignment.CenterEnd) {
                if (position != null) WLText(position.resolve(), WLType.label.copy(fontSize = 14.sp), color = LocalWLColors.current.textSecondary, maxLines = 1)
            }
        },
        title = { WLText(title, WLType.bodyStrong.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
    )
}

/** 보드 하단 버튼(높이 56 pill): 보조는 카드색 500, 주는 반전색 700. 아이콘은 글자 앞(보류) 또는 뒤(확정). */
@Composable
internal fun FlowButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: WLLineIcon? = null,
    trailingIcon: WLLineIcon? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    horizontalPadding: Dp = WishlistTokens.Space.s16,
) {
    val c = LocalWLColors.current
    val fg = if (primary) c.onInverse else c.text
    Row(
        modifier
            .heightIn(min = height)
            .unavailable(!enabled)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .background(if (primary) c.text else c.card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let { WLIcon(it, color = fg) }
        WLText(text, WLType.button.copy(fontSize = 16.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium), color = fg, maxLines = 1)
        trailingIcon?.let { WLIcon(it, color = fg) }
    }
}

/** C8 진입점: DISABLED_ALPHA로 흐리고 TalkBack은 "사용할 수 없음"을 읽는다(누를 수 없음은 호출하는 쪽의 `enabled = false`). */
@Composable
internal fun Modifier.unavailable(disabled: Boolean = true): Modifier {
    if (!disabled) return this
    val text = stringResource(R.string.flow_unavailable)
    return this.alpha(DISABLED_ALPHA).semantics { stateDescription = text }
}

/** 버튼 위 13 보조색 가운데 안내 줄(여러 줄이면 각각). */
@Composable
internal fun ColumnScope.FlowHints(vararg lines: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = WishlistTokens.Space.s12),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        lines.forEach { WLText(it, WLType.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal), color = LocalWLColors.current.textSecondary, textAlign = TextAlign.Center) }
    }
}

/**
 * 끝 요약(보드 done): 64 완료 타일 · 24/700 제목 · 15 보조색 요약 · "홈으로"(먹색 52) · "처음부터 다시 보기"(14 보조색).
 * [appear]: 리뷰는 다음 카드 자리 모션(scale .94 → 1, 300 `emphasized`)으로 나타난다(motion.md §4 구현 기본값).
 */
@Composable
internal fun FlowSummary(title: String, summary: ResText, onRestart: () -> Unit, appear: Boolean) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val dark = LocalWLDark.current
    val scale = remember { Animatable(if (appear) CardThrowMath.NEXT_START_SCALE else 1f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(CardThrowMath.NEXT_MILLIS, easing = WishlistTokens.Curve.emphasized)) }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .padding(start = 24.dp, end = 24.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        val surface = if (dark) WishlistTokens.Status.Dark.doneSurface else WishlistTokens.Status.Light.doneSurface
        val icon = if (dark) WishlistTokens.Status.Dark.doneIcon else WishlistTokens.Status.Light.doneIcon
        WLIconTile(size = 64.dp, radius = WishlistTokens.Radius.l, color = surface) { WLIcon(WLLineIcon.CheckBold, size = 28.dp, color = icon) }
        WLText(title, WLType.title.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        WLText(summary.resolve(), WLType.body.copy(fontSize = 15.sp), color = c.textSecondary, textAlign = TextAlign.Center)
        FlowButton(stringResource(R.string.flow_home), primary = true, onClick = { nav.pop() },
            modifier = Modifier.padding(top = 12.dp), height = 52.dp, horizontalPadding = 28.dp)
        Box(
            Modifier.heightIn(min = WishlistTokens.Space.minTouch).clickable(role = Role.Button, onClick = onRestart).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { WLText(stringResource(R.string.flow_restart), WLType.body.copy(fontSize = 14.sp), color = c.textSecondary) }
    }
}
