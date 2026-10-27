package app.wishlist.android.feature.flow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.home.HomeRowText
import app.wishlist.android.feature.web.WebViewRoute
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.HomeFlowPhase
import app.wishlist.shared.presentation.HomeFlowPresenter
import app.wishlist.shared.presentation.HomeFlowState
import kotlinx.coroutines.delay

/** 보완 넘김: 내용만 320 `standard`로 현재 0 → -W, 다음 +W → 0(motion.md §5). */
private const val FILL_SLIDE_MILLIS = 320

/** What the sliding area shows: a card, the summary, or nothing yet (first read). */
private sealed interface FillPage {
    data class Item(val item: WishlistItem) : FillPage
    data object Summary : FillPage
    data object Empty : FillPage
}

/**
 * 정보 보완(FHomeFillFlow, C7 사용자 답변 2): `건너뛰기`만 동작한다(`pass()` + 내용 밀기). `저장하고 다음`·`다시 분석하기`·
 * 사진·이름·카테고리 입력은 보드 배치 그대로 비활성(흐림·"사용할 수 없음")이고 버튼 위에 저장은 다음 단계라는 안내가 있다.
 * 머리(닫기·`n / 전체`)와 아래 버튼은 움직이지 않는다. 위치는 전환 시작 때 새 값이고, 전환 중(320)에는 버튼을 받지 않는다.
 * `원본`은 앱 안 웹뷰(C4)다.
 */
@Composable
internal fun FillFlowScreen(title: String, state: HomeFlowState, presenter: HomeFlowPresenter) {
    val done = state.phase == HomeFlowPhase.DONE
    val current = state.current
    FlowHeader(title, position = if (!done && current != null) HomeFlowText.position(state.position, state.total) else null)
    val page: FillPage = when {
        done -> FillPage.Summary
        current != null -> FillPage.Item(current.item)
        else -> FillPage.Empty
    }
    var locked by remember { mutableStateOf(false) }
    var skips by remember { mutableIntStateOf(0) }
    // Unlocks 320 ms after the skip, restarted when the slide actually begins (the page changed); a pass the
    // Presenter ignored (no page change) still unlocks after 320 ms.
    LaunchedEffect(page, skips) {
        if (!locked) return@LaunchedEffect
        delay(FILL_SLIDE_MILLIS.toLong())
        locked = false
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            transitionSpec = {
                val spec = tween<androidx.compose.ui.unit.IntOffset>(FILL_SLIDE_MILLIS, easing = WishlistTokens.Curve.standard)
                slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it }
            },
            contentKey = { (it as? FillPage.Item)?.item?.id ?: it::class.simpleName },
            label = "fill",
        ) { shown ->
            when (shown) {
                is FillPage.Item -> FillContent(shown.item)
                FillPage.Summary -> FlowSummary(stringResource(R.string.flow_fill_done), HomeFlowText.skippedSummary(state.passed), presenter::restart, appear = false)
                FillPage.Empty -> Box(Modifier.fillMaxSize())
            }
        }
        if (!done) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = flowBottomPadding())) {
                FlowHints(stringResource(R.string.flow_fill_not_saved))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowButton(
                        stringResource(R.string.flow_fill_skip), primary = false,
                        onClick = {
                            if (!locked && state.phase == HomeFlowPhase.ACTIVE) {
                                locked = true
                                skips++
                                keyboard?.hide()
                                focus.clearFocus(force = true)
                                presenter.pass()
                            }
                        },
                        modifier = Modifier.weight(1f), enabled = current != null,
                    )
                    FlowButton(stringResource(R.string.flow_fill_save_next), primary = true, onClick = {}, modifier = Modifier.weight(2f), enabled = false)
                }
            }
        }
    }
}

/** 한 상품의 보완 내용(보드 순서). 새 상품은 맨 위부터 보인다(내용마다 새 스크롤 상태). */
@Composable
private fun FillContent(item: WishlistItem) {
    val c = LocalWLColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ReasonCard(item)
        if (item.requiredAction == RequiredAction.INFORMATION_COMPLETION) {
            FlowButton(stringResource(R.string.flow_fill_reanalyze), primary = false, onClick = {}, modifier = Modifier.fillMaxWidth(),
                leadingIcon = WLLineIcon.Reload, enabled = false, height = 52.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
                WLText(stringResource(R.string.flow_fill_or_manual), FieldLabel, color = c.textSecondary)
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
            }
        }
        FillField(stringResource(R.string.flow_fill_image_label)) {
            val url = item.product.imageUrl
            if (url == null) {
                Column(
                    Modifier.fillMaxWidth().height(120.dp).unavailable().dashedBorder(c.underline, WishlistTokens.Radius.m.value),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    WLIcon(WLLineIcon.Image, size = 24.dp, color = c.textSecondary)
                    WLText(stringResource(R.string.flow_fill_image_add), WLType.body, color = c.textSecondary)
                }
            } else {
                ProductPhoto(url, Modifier.size(120.dp).unavailable().clip(RoundedCornerShape(WishlistTokens.Radius.m)))
            }
        }
        FillField(stringResource(R.string.flow_fill_name_label)) {
            val name = item.product.name?.takeIf { it.isNotBlank() }
            FieldBox {
                WLText(name ?: stringResource(R.string.flow_fill_name_placeholder), WLType.body.copy(fontSize = 16.sp),
                    color = if (name != null) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FillField(stringResource(R.string.flow_fill_category_label)) {
            val category = item.category.name
            FieldBox(endPadding = 14.dp) {
                WLText(category ?: stringResource(R.string.flow_fill_category_choose), WLType.body.copy(fontSize = 16.sp), Modifier.weight(1f),
                    color = if (category != null) c.text else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                WLIcon(WLLineIcon.ChevronRight, size = 18.dp)
            }
        }
    }
}

private val FieldLabel = WLType.body.copy(fontSize = 13.sp)

/** 보완 이유 카드: 실패 상태색 44 타일(연필) · 이유 15/700 · host 12/500 · `원본`(앱 안 웹뷰, http/https가 아니면 흐림). */
@Composable
private fun ReasonCard(item: WishlistItem) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val dark = LocalWLDark.current
    val route = remember(item.sourceUrl) { WebViewRoute.of(item.sourceUrl) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WishlistTokens.Radius.l)).background(c.card).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(color = if (dark) WishlistTokens.Status.Dark.failedSurface else WishlistTokens.Status.Light.failedSurface) {
            WLIcon(WLLineIcon.Pen, color = if (dark) WishlistTokens.Status.Dark.failedIcon else WishlistTokens.Status.Light.failedIcon)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val reason = HomeFlowText.fillReason(item.requiredAction, item.product.name.isNullOrBlank(), item.analysis.status)
            WLText(stringResource(reason), WLType.bodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
            WLText(DisplayFormat.host(item.sourceUrl), WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier
                .heightIn(min = WishlistTokens.Space.minTouch)
                .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
                .unavailable(route == null)
                .clickable(enabled = route != null, role = Role.Button, onClickLabel = stringResource(HomeRowText.openOriginal)) {
                    route?.let { nav.push(it, "home/flow/${item.id}/original") }
                }
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WLText(stringResource(R.string.home_original), WLType.body, maxLines = 1)
            WLIcon(WLLineIcon.External, size = 16.dp)
        }
    }
}

/** 이름 13 보조색 + 8 + 칸. */
@Composable
private fun FillField(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WLText(label, FieldLabel, color = LocalWLColors.current.textSecondary)
        content()
    }
}

/** 입력칸 자리(높이 56, 모서리 20, 카드색, 왼쪽 18). C8 전까지 입력하지 않는 비활성 칸이다. */
@Composable
private fun FieldBox(endPadding: androidx.compose.ui.unit.Dp = 18.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .unavailable()
            .clip(RoundedCornerShape(WishlistTokens.Radius.m))
            .background(LocalWLColors.current.card)
            .padding(start = 18.dp, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 1.5 점선 테두리(보드 `1.5px dashed`, 4·3 간격). */
private fun Modifier.dashedBorder(color: androidx.compose.ui.graphics.Color, radiusDp: Float): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radiusDp * density),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
