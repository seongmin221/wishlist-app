package app.wishlist.android.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.DISABLED_ALPHA
import app.wishlist.android.feature.detail.DetailNoticeLine
import app.wishlist.android.feature.detail.rememberBriefNotice
import app.wishlist.android.feature.web.WebViewRoute
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.navigation.WLTabBarHeight
import app.wishlist.android.navigation.wlTabBarBottomPadding
import app.wishlist.android.ui.ItemDetailRoute
import app.wishlist.android.ui.LocalSubmissionRoute
import app.wishlist.android.ui.SettingsRoute
import app.wishlist.shared.presentation.HomeRowTarget
import app.wishlist.shared.presentation.HomeRow
import app.wishlist.shared.presentation.HomeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 홈 탭 첫 화면. 로그인 전은 FHomeLoggedOut(로그인 카드 + 분석 대기), 로그인 뒤는 FHome(C7: 분석 대기 · HOME-01 할 일
 * 카드 · 비교 중인 목적). 당겨서 새로고침이 재전송 + HOME-01이다. `Loading`(복원 전·계정 전환 중)은 머리만 그려 이전 계정의
 * 줄이 비치지 않게 한다.
 * - 화면이 보일 때마다(진입·탭 전환·덮던 화면 pop — composition 진입) `appeared()`로 HOME-01을 읽는다(C6 재등장 패턴).
 * - 이 화면이 보이는 동안 앱이 돌아오면(두 번째 이후 resume) `foreground()`. 앱 전체 신호의 전송 refresh와 겹치는 것은
 *   조정기 single-flight가 합친다(Ruling 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val owner = LocalHomeOwner.current
    val state by owner.state.collectAsState()
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    LaunchedEffect(owner) {
        runtime.ready.first { it }
        owner.appeared()
    }
    var resumes by remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes++ > 0 && runtime.ready.value) owner.foreground()
        onPauseOrDispose { }
    }
    // "방금" → "1분 전" while the screen stays composed (cancelled when it leaves).
    LaunchedEffect(owner) {
        while (true) {
            delay(60_000)
            owner.tick()
        }
    }
    val c = LocalWLColors.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Home, scroll)
    // A later HOME-01 failure keeps the last summary and shows a short notice.
    val failures = remember(owner) { mutableIntStateOf(0) }
    val refreshFailed = (state as? HomeState.LoggedIn)?.refreshFailed == true
    LaunchedEffect(refreshFailed) { if (refreshFailed) failures.intValue++ }
    val notice = rememberBriefNotice(failures.intValue.takeIf { it > 0 }, stringResource(R.string.home_refresh_failed))

    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = WishlistTokens.Space.screenMargin),
        ) {
            HomeHeader(
                caption = when (val s = state) {
                    HomeState.Loading -> null
                    is HomeState.LoggedOut -> stringResource(R.string.home_logged_out_caption)
                    is HomeState.LoggedIn -> HomeRowText.todoCount(s.todoCount).resolve()
                },
            )
            when (val s = state) {
                HomeState.Loading -> Unit
                is HomeState.LoggedOut -> HomeLoggedOutContent(s)
                is HomeState.LoggedIn -> HomeLoggedInContent(s, onRetry = owner::retry)
            }
            Spacer(Modifier.height(WLTabBarHeight + wlTabBarBottomPadding() + WishlistTokens.Space.s16))
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        val loggedIn = state as? HomeState.LoggedIn
        if (loggedIn != null) {
            PullToRefreshBox(isRefreshing = loggedIn.refreshing, onRefresh = owner::refresh, modifier = Modifier.fillMaxSize()) {
                content()
            }
        } else {
            content()
        }
        DetailNoticeLine(notice, Modifier.align(Alignment.TopCenter))
    }
}

/** 머리(FHome header): 제목 도현 28이 위쪽 바 세로 가운데, 오른쪽 설정 원형 버튼. 아래 13/500 보조 줄(로그인 전 "로그인 전", 로그인 뒤 "할 일 N개"). */
@Composable
private fun HomeHeader(caption: String?) {
    val nav = LocalWLNavigator.current
    Column(Modifier.fillMaxWidth()) {
        WLTopBar(
            sideMargin = 0.dp,
            trailing = {
                WLCircleButton(WLLineIcon.Settings, stringResource(R.string.settings_title), onClick = {
                    nav.push(SettingsRoute, "home/settings")
                })
            },
            title = { WLText(stringResource(R.string.wl_tab_home), WLType.display28, maxLines = 1) },
        )
        if (caption != null) WLText(caption, HomeStyles.caption, color = LocalWLColors.current.textSecondary, maxLines = 1)
    }
}

/** 보드 FHome·FHomeLoggedOut의 글자 크기(토큰에 없는 크기는 가장 가까운 WLType에서 크기·굵기만 바꾼다). */
internal object HomeStyles {
    val caption = WLType.label.copy(fontSize = 13.sp)
    val sectionLabel = WLType.label.copy(fontSize = 13.sp)
    val cardTitle = WLType.bodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    val cardSubtitle = WLType.body.copy(fontSize = 13.sp)
    val bullet = WLType.body.copy(lineHeight = 1.6f.em)
    val count = WLType.price.copy(fontSize = 26.sp)
    val link = WLType.body.copy(fontSize = 13.sp)
}

/** 섹션: 위 간격 32, 13/500 이름, 아래 내용과 12. */
@Composable
internal fun HomeSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = WishlistTokens.Space.s32), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
        WLText(label, HomeStyles.sectionLabel, color = LocalWLColors.current.textSecondary)
        content()
    }
}

/**
 * 펼친 카드 안의 링크 한 줄: 묶음 면(`sheetField`) 위 아이콘 타일·host·상태 줄. `showsOriginal`이면 오른쪽에 "원본"
 * (로그인 전 분석 대기만, Ruling 15: 로그인 뒤 분류 중 줄은 오른쪽 동작이 없다 — 보드의 삭제는 C4/C8).
 * 줄을 누르면 상세로 간다(C4): 서버 상품은 `ItemDetailRoute`, 이 기기의 링크는 `LocalSubmissionRoute`(가로 밀기).
 */
@Composable
internal fun HomeLinkRow(row: HomeRow, icon: WLLineIcon, tileSize: Dp, showsOriginal: Boolean = false) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WishlistTokens.Radius.m))
            .background(c.sheetField)
            .clickable(role = Role.Button, onClickLabel = stringResource(HomeRowText.openDetail)) {
                when (val target = row.target) {
                    is HomeRowTarget.Item -> nav.push(ItemDetailRoute(target.itemId), "home/row/${row.key}")
                    is HomeRowTarget.Local -> nav.push(LocalSubmissionRoute(target.submissionId), "home/row/${row.key}")
                }
            }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(size = tileSize, color = c.card) { WLIcon(icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WLText(row.host, WLType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WLText(row.metaText(), WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showsOriginal) OriginalLink(row.sourceUrl, "home/row/${row.key}/original")
    }
}

/** "원본": C4 PR B — 앱 안 웹뷰([WebViewRoute])로 연다. `http`/`https`가 아니면 흐리게 두고 누를 수 없다. */
@Composable
private fun OriginalLink(url: String, sourceKey: String) {
    val nav = LocalWLNavigator.current
    val route = remember(url) { WebViewRoute.of(url) }
    Row(
        Modifier
            .heightIn(min = WishlistTokens.Space.minTouch)
            .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
            .alpha(if (route != null) 1f else DISABLED_ALPHA)
            .clickable(enabled = route != null, role = Role.Button, onClickLabel = stringResource(HomeRowText.openOriginal)) {
                route?.let { nav.push(it, sourceKey) }
            }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLText(stringResource(R.string.home_original), HomeStyles.link, maxLines = 1)
        WLIcon(WLLineIcon.External, size = 14.dp)
    }
}


/** 카드 펼침 상태는 화면 상태다(Presenter에 두지 않는다). */
@Composable
internal fun rememberExpanded(key: String) = rememberSaveable(key) { androidx.compose.runtime.mutableStateOf(false) }
