package app.wishlist.android.feature.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLChevron
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.navigation.WLTabBarHeight
import app.wishlist.android.navigation.wlTabBarBottomPadding
import app.wishlist.android.ui.SettingsRoute
import app.wishlist.shared.presentation.HomeRow
import app.wishlist.shared.presentation.HomeState

/**
 * 홈 탭 첫 화면(C3). 로그인 전은 FHomeLoggedOut(로그인 카드 + 분석 대기), 로그인 뒤는 FHome 틀에 "분류 중" 카드만
 * (C3-D3: 이 계정의 미전송 + 캐시의 분석 중 항목, 다른 할 일 카드는 C7까지 숨김) 당겨서 새로고침이 재전송+갱신이다.
 * `Loading`(복원 전·계정 전환 중)은 머리만 그려 이전 계정의 줄이 비치지 않게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val owner = LocalHomeOwner.current
    val state by owner.state.collectAsState()
    val c = LocalWLColors.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Home, scroll)

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
                    is HomeState.LoggedIn -> HomeRowText.todoCount(s.processing.size).resolve()
                },
            )
            when (val s = state) {
                HomeState.Loading -> Unit
                is HomeState.LoggedOut -> HomeLoggedOutContent(s)
                is HomeState.LoggedIn -> HomeLoggedInContent(s)
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
 * 할 일 카드(분석 대기·분류 중): 갈 화면이 없어 카드 머리 전체가 펼치기다(screens.md 홈). 화살표 200 `ease`,
 * 펼침 내용 높이·opacity 200 `ease`(motion.md 작은 동작).
 */
@Composable
internal fun HomeTodoCard(
    icon: WLLineIcon,
    title: String,
    subtitle: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWLColors.current
    val description = stringResource(if (expanded) R.string.wl_expanded else R.string.wl_collapsed)
    WLCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = description }
                    .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).heightIn(min = WishlistTokens.Space.minTouch),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WLIconTile { WLIcon(icon) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        WLText(title, HomeStyles.cardTitle)
                        WLText(subtitle, HomeStyles.cardSubtitle, color = c.textSecondary)
                    }
                    WLText(count.toString(), HomeStyles.count, maxLines = 1)
                }
                Box(Modifier.size(WishlistTokens.Space.minTouch), contentAlignment = Alignment.Center) { WLChevron(expanded) }
            }
            val spec = tween<Float>(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeIn(spec),
                exit = shrinkVertically(tween(WishlistTokens.Motion.disclosureContent, easing = WishlistTokens.Curve.ease)) + fadeOut(spec),
            ) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
                    content = rows,
                )
            }
        }
    }
}

/**
 * 펼친 카드 안의 링크 한 줄: 묶음 면(`sheetField`) 위 아이콘 타일·host·상태 줄. `showsOriginal`이면 오른쪽에 "원본"
 * (로그인 전 분석 대기만, Ruling 15: 로그인 뒤 분류 중 줄은 오른쪽 동작이 없다 — 보드의 삭제는 C4/C8).
 */
@Composable
internal fun HomeLinkRow(row: HomeRow, icon: WLLineIcon, tileSize: Dp, showsOriginal: Boolean = false) {
    val c = LocalWLColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WishlistTokens.Radius.m))
            .background(c.sheetField)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(size = tileSize, color = c.card) { WLIcon(icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WLText(row.host, WLType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WLText(row.metaText(), WLType.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showsOriginal) OriginalLink(row.sourceUrl)
    }
}

/** "원본": C3-D5 a — 시스템 브라우저로 연다(C4에서 웹뷰). */
@Composable
private fun OriginalLink(url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .heightIn(min = WishlistTokens.Space.minTouch)
            .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
            .clickable(role = Role.Button) { openOriginal(context, url) }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLText(stringResource(R.string.home_original), HomeStyles.link, maxLines = 1)
        WLIcon(WLLineIcon.External, size = 14.dp)
    }
}

// Uri.parse: core-ktx is only a transitive dependency of this module.
@Suppress("UseKtx")
private fun openOriginal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser installed: nothing to open (the row stays).
    }
}

/** 카드 펼침 상태는 화면 상태다(Presenter에 두지 않는다). */
@Composable
internal fun rememberExpanded(key: String) = rememberSaveable(key) { androidx.compose.runtime.mutableStateOf(false) }
