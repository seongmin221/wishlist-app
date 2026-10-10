package app.wishlist.android.feature.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLTopBarMetrics
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.wlSafeTop
import app.wishlist.android.feature.web.WebViewRoute
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 상세 화면(FProductDetail·FProductProcessing)의 글자 크기. 토큰에 없는 크기는 가장 가까운 WLType에서 크기·굵기만 바꾼다. */
internal object DetailStyles {
    val brand = WLType.body.copy(fontWeight = FontWeight.Bold)
    val name = WLType.title
    val price = WLType.price.copy(fontSize = 24.sp)
    val priceNote = WLType.body.copy(fontSize = 13.sp, lineHeight = 1.5.em)
    val note = WLType.body.copy(lineHeight = 1.6.em)
    val caption = WLType.label.copy(fontSize = 13.sp)
    val infoValue = WLType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val notice = WLType.body.copy(fontSize = 13.sp)
}

/** 하단 바 아래 여백: 보드 36(홈 표시줄 포함). 내비게이션 막대가 더 높으면 그 위 12(구현 기본값). */
@Composable
private fun bottomBarBottomPadding(): Dp =
    max(36.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + WishlistTokens.Space.s12)

/** 하단 바 높이(위 12 + 버튼 56 + 아래 여백). 스크롤 내용 끝을 이만큼 + 20 비운다(보드 padding-bottom 140). */
@Composable
private fun bottomBarHeight(): Dp = WishlistTokens.Space.s12 + 56.dp + bottomBarBottomPadding()

/** 위쪽 바 버튼 아래 12(안전 영역 + 68): 사진 칸·첫 내용이 시작하는 자리. */
@Composable
internal fun detailContentTop(): Dp = wlSafeTop() + WLTopBarMetrics.ButtonTop + WishlistTokens.Space.minTouch + WishlistTokens.Space.s12

/** 당겨서 새로고침(서버 상품 상세만). */
internal class DetailRefresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/**
 * 상세 공통 틀: 스크롤 본문 위에 뒤로·(선택)⋯가 떠 있는 위쪽 바 56(안전 영역 + 6, 좌우 20), 하단 고정 "원본 보기"
 * (PR B: 앱 안 웹뷰 [WebViewRoute], `http`/`https`가 아니면 누를 수 없다). 탭 바는 route가 숨긴다. `originalUrl`이 없으면(첫 로딩·오류) 하단 바를 그리지 않는다.
 * `notice`는 위쪽 바 아래의 짧은 안내 줄이다(C1에 토스트 부품이 없다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    onBack: () -> Unit,
    originalUrl: String?,
    modifier: Modifier = Modifier,
    more: (@Composable () -> Unit)? = null,
    notice: String? = null,
    refresh: DetailRefresh? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWLColors.current
    Box(modifier.fillMaxSize().background(c.background)) {
        val body: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(detailContentTop()))
                content()
                Spacer(Modifier.height(if (originalUrl != null) bottomBarHeight() + WishlistTokens.Space.s20 else WishlistTokens.Space.s40))
            }
        }
        if (refresh != null) {
            PullToRefreshBox(isRefreshing = refresh.refreshing, onRefresh = refresh.onRefresh, modifier = Modifier.fillMaxSize()) { body() }
        } else {
            body()
        }
        WLTopBar(
            leading = { WLCircleButton(WLLineIcon.Back, stringResource(R.string.detail_back), onClick = onBack) },
            trailing = more?.let { { it() } },
        )
        DetailNoticeLine(notice, Modifier.align(Alignment.TopCenter))
        if (originalUrl != null) OriginalBar(originalUrl, Modifier.align(Alignment.BottomCenter))
    }
}

/** 웹 주소가 아닌 원본 링크: 버튼은 남기되 누를 수 없게 흐리게 그린다. */
internal const val DISABLED_ALPHA = 0.4f

/** 하단 고정 "원본 보기": 위 12·좌우 20, 높이 56 pill 반전색, 16/700 + 바깥 링크 18(간격 8). */
@Composable
private fun OriginalBar(url: String, modifier: Modifier) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val route = remember(url) { WebViewRoute.of(url) }
    Box(
        modifier
            .fillMaxWidth()
            .background(c.background)
            .padding(start = 20.dp, end = 20.dp, top = WishlistTokens.Space.s12, bottom = bottomBarBottomPadding()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
                .background(c.text)
                .alpha(if (route != null) 1f else DISABLED_ALPHA)
                .clickable(enabled = route != null, role = Role.Button) { route?.let { nav.push(it, "detail/original") } },
            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WLText(stringResource(R.string.detail_open_original), WLType.button, color = c.onInverse, maxLines = 1)
            WLIcon(WLLineIcon.External, size = 18.dp, color = c.onInverse)
        }
    }
}

/** 짧은 안내 줄: 위쪽 바 아래, 카드색 pill 13/400. 나타남·사라짐 opacity 200(구현 기본값). 웹뷰 "링크를 복사했어요"도 쓴다. */
@Composable
internal fun DetailNoticeLine(text: String?, modifier: Modifier) {
    val c = LocalWLColors.current
    var last by remember { mutableStateOf(text) }
    if (text != null) last = text
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(WishlistTokens.Motion.dialogIn)),
        exit = fadeOut(tween(WishlistTokens.Motion.dialogIn)),
        modifier = modifier.padding(top = wlSafeTop() + WLTopBarMetrics.Bottom + WishlistTokens.Space.s8),
    ) {
        WLText(
            last.orEmpty(),
            DetailStyles.notice,
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
                .background(c.card)
                .padding(horizontal = WishlistTokens.Space.s16, vertical = 10.dp),
            color = c.text,
            maxLines = 2,
        )
    }
}

/** `key`가 바뀔 때마다(null 아님) 안내를 3초 보이고 지운다. */
@Composable
internal fun rememberBriefNotice(key: Any?, text: String): String? {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        shown = text
        delay(3_000)
        shown = null
    }
    return shown
}

/** 상태 화면(항목 없는 오류·삭제된 상품): 경고 타일 + 18/700 문장 + 버튼 하나, 본문 영역 가운데. */
@Composable
internal fun DetailStatusBlock(message: String, buttonText: String, onClick: () -> Unit) {
    val c = LocalWLColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WishlistTokens.Space.s32, vertical = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16),
    ) {
        WLIconTile(size = 56.dp, radius = WishlistTokens.Radius.m, color = c.card) { WLIcon(WLLineIcon.Warning, color = c.textSecondary) }
        WLText(message, WLType.title.copy(fontSize = 18.sp), color = c.text, textAlign = TextAlign.Center)
        WLButton(buttonText, WLButtonKind.Secondary, onClick = onClick)
    }
}

/**
 * Runs a stack change for the screen showing [route] once no transition is running ([WLNavigator.pop] and
 * [WLNavigator.replaceTop] refuse during one, e.g. a `MovedTo` that arrives while the screen is still sliding in).
 * Gives up when [route] is no longer the current top (the user left, or the shell dropped it).
 */
internal suspend fun WLNavigator.whenSettled(route: WLRoute, change: WLNavigator.() -> Boolean) {
    while (true) {
        snapshotFlow { isTransitioning }.first { !it }
        if (stack(currentTab).lastOrNull() != route) return
        if (change()) return
    }
}
