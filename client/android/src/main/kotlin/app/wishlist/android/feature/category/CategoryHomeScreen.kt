package app.wishlist.android.feature.category

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.EmptyState
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLAddChip
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLChip
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.ui.LoginRoute
import app.wishlist.shared.model.CategoryGroup
import app.wishlist.shared.presentation.CategoryFormPresenter
import app.wishlist.shared.presentation.CategoryHomePresenter
import app.wishlist.shared.presentation.CategoryListHint
import app.wishlist.shared.presentation.FormResult
import app.wishlist.shared.presentation.FormSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** 레일 폭 124(FCategoryHomeL nav). */
private val RailWidth = 124.dp

/** 페이지 넘김·레일 이동 모션: 300 `emphasized`(디자인 결정 2026-10-07). */
private val PageSpec = tween<Float>(Motion.headerCollapseScroll, easing = Curve.emphasized)

/** 넘치는 페이지 끝에서 놓을 때 다음·이전 페이지로 넘기는 속도(구현 기본값). */
private val PageFlingThreshold = 400.dp

/**
 * 카테고리 탭 첫 화면(FCategoryHome, C5 spec §3). CAT-01 BROWSE의 상위 하나가 한 페이지인 세로 페이저와 왼쪽 레일.
 * - 로그인 전(D5): 안내 + 로그인. 상품·직접 만든 카테고리 없음(D6): 빈 상태 + "새 카테고리 만들기".
 * - 페이지를 넘기거나 레일을 누르면 그 상위를 기억한다(계정별, 다시 열 때 그 페이지).
 * - 칩 → 세부 유형 목록(가로 밀기, 탭 바 유지). 점선 `+ 추가` → 지금 페이지 상위로 신규 카테고리 시트.
 * - 갱신: 진입, 당겨서 새로고침(첫 페이지 위), 두 번째 이후 resume(foreground), 생성 뒤.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryHomeScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(storeOwner) { CategoryPresenterOwner.of(storeOwner, "category.home") { runtime.categoryHomePresenter() } }
    val formSlot = remember(storeOwner) { CategoryFormSlot.of(storeOwner, "category.home.form") { runtime.categoryFormPresenter() } }
    val home: CategoryHomePresenter = owner.presenter
    val state by home.state.collectAsState()

    LaunchedEffect(owner) {
        runtime.ready.first { it }
        owner.once { it.load() }
    }
    val resumes = remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes.intValue++ > 0) home.refresh()
        onPauseOrDispose { }
    }

    // An edit saved on a list screen: refresh the chips. The owner remembers the revision it has shown,
    // because this root's composition may be gone while the list covers it (then the check runs on return).
    LaunchedEffect(owner) {
        CategoryChanges.revision.collect { revision -> if (owner.markSeen(revision)) home.refresh() }
    }
    var pulling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A group to show once the list holds it (a category just made under a group that had no page yet).
    var focusGroup by remember { mutableStateOf<String?>(null) }
    val discard = rememberDiscardSpec { overlay.dismissAll() }
    val openCreate: (String?) -> Unit = { parent ->
        overlay.showCategoryForm(formSlot.fresh(), discard, start = { it.startCreate(parent, FormSource.TAB, null) }) { result ->
            if (result is FormResult.Created) {
                focusGroup = result.detail.parentId // D9: that group's page, the new chip at its end
                home.refresh()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.padding(start = WishlistTokens.Space.screenMargin, end = WishlistTokens.Space.screenMargin, bottom = WishlistTokens.Space.s16)) {
            Column(Modifier.fillMaxWidth()) {
                WLTopBar(sideMargin = 0.dp, title = { WLText(stringResource(R.string.category_title), WLType.display28, maxLines = 1) })
                if (state.groups.isNotEmpty()) {
                    WLText(stringResource(R.string.category_total, state.totalCount), CategoryCaption, color = c.textSecondary, maxLines = 1)
                }
            }
        }
        when {
            !state.signedIn -> CenterBlock {
                EmptyState(stringResource(R.string.category_title), keepAllBreaks(stringResource(R.string.category_signed_out_body))) {
                    WLIcon(WLLineIcon.Apps, size = 24.dp)
                }
                WLButton(stringResource(R.string.category_signed_out_action), WLButtonKind.Primary, onClick = { nav.push(LoginRoute, "category/login") })
            }
            state.groups.isEmpty() && state.error != null -> CenterBlock {
                DetailStatusBlock(stringResource(DetailText.errorText(state.error!!)), stringResource(R.string.detail_retry), home::retry)
            }
            state.groups.isEmpty() && state.loaded && !state.loading -> CenterBlock {
                EmptyState(stringResource(R.string.category_empty_title), keepAllBreaks(stringResource(R.string.category_empty_body))) {
                    WLIcon(WLLineIcon.Apps, size = 24.dp)
                }
                WLButton(stringResource(R.string.category_empty_action), WLButtonKind.Secondary, onClick = { openCreate(null) })
            }
            state.groups.isEmpty() -> Box(Modifier.fillMaxSize()) // first load: nothing yet (no flash of the empty state)
            else -> {
                CategoryDivider()
                // The indicator follows a user pull only (resume, edit and create refresh quietly).
                PullToRefreshBox(
                    isRefreshing = pulling,
                    onRefresh = {
                        pulling = true
                        scope.launch { try { home.refreshNow() } finally { pulling = false } }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    GroupPager(
                        groups = state.groups,
                        currentGroupId = state.currentGroupId,
                        focusGroup = focusGroup,
                        onFocused = { focusGroup = null },
                        onShowGroup = home::showGroup,
                        onChip = { group, entry ->
                            nav.push(
                                CategoryListRoute(CategoryListHint(entry.id, entry.name, group.id, group.name, entry.kind)),
                                "category/${entry.id}",
                            )
                        },
                        onAdd = { group -> openCreate(group.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterBlock(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = tabBarClearance()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun GroupPager(
    groups: List<CategoryGroup>,
    currentGroupId: String?,
    focusGroup: String?,
    onFocused: () -> Unit,
    onShowGroup: (String) -> Unit,
    onChip: (CategoryGroup, app.wishlist.shared.model.CategoryEntry) -> Unit,
    onAdd: (CategoryGroup) -> Unit,
) {
    val nav = LocalWLNavigator.current
    val startPage = groups.indexOfFirst { it.id == currentGroupId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { groups.size }
    // The rail target while a rail tap animates (so pages in between do not flash as selected).
    var anchor by remember { mutableStateOf<Int?>(null) }
    val selected = (anchor ?: pager.currentPage).coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val goTo: (Int) -> Unit = { i ->
        anchor = i
        scope.launch {
            try {
                pager.animateScrollToPage(i, animationSpec = PageSpec)
            } finally {
                if (anchor == i) anchor = null
            }
        }
    }
    val density = LocalDensity.current
    val overflowToPager = remember(pager, density) { OverflowToPager(pager, scope, with(density) { PageFlingThreshold.toPx() }) }

    // The group the pager itself last settled on. A refresh that changes the groups restores this one,
    // not the Presenter's current group: that one lags a fling by a lane hop, and restoring it would
    // snap the page back right after a fast swipe.
    val shownGroup = remember { mutableStateOf(groups.getOrNull(startPage)?.id) }
    val latestGroups by androidx.compose.runtime.rememberUpdatedState(groups)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            latestGroups.getOrNull(page)?.let { shownGroup.value = it.id; onShowGroup(it.id) }
        }
    }
    LaunchedEffect(groups) {
        val want = groups.indexOfFirst { it.id == shownGroup.value }
        if (want >= 0 && want != pager.currentPage && anchor == null && !pager.isScrollInProgress) pager.scrollToPage(want)
    }
    LaunchedEffect(groups, focusGroup) {
        val target = groups.indexOfFirst { it.id == focusGroup }
        if (focusGroup != null && target >= 0) {
            goTo(target)
            onFocused()
        }
    }
    // Re-tapping the category tab while this root is on top: first page.
    LaunchedEffect(nav) {
        nav.scrollToTopRequests.collect { if (it == WLTab.Category && nav.stack(WLTab.Category).size == 1) goTo(0) }
    }

    Row(Modifier.fillMaxSize()) {
        Rail(groups, selected, goTo)
        VerticalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PageSpec),
            key = { groups[it].id },
        ) { page ->
            val inner = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(overflowToPager)
                    .verticalScroll(inner, enabled = inner.maxValue > 0)
                    .padding(start = WishlistTokens.Space.s8, end = WishlistTokens.Space.s16, top = WishlistTokens.Space.s8, bottom = tabBarClearance()),
            ) {
                GroupSection(groups[page], onChip, onAdd)
            }
        }
    }
}

/**
 * 넘치는 페이지(칩이 많거나 큰 글자)의 안쪽 스크롤이 끝에 닿은 뒤 남은 끌기를 페이저로 넘긴다(C1 데모와 같은 규칙).
 * 놓을 때 페이지 사이면 속도(기준 이상이면 그 방향)·거리(가까운 쪽)로 스냅한다.
 */
private class OverflowToPager(
    private val pager: PagerState,
    private val scope: CoroutineScope,
    private val flingThresholdPx: Float,
) : NestedScrollConnection {
    private val between get() = abs(pager.currentPageOffsetFraction) > 0.001f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && between) Offset(0f, -pager.dispatchRawDelta(-available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y != 0f) Offset(0f, -pager.dispatchRawDelta(-available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!between) return Velocity.Zero
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val target = when {
            available.y < -flingThresholdPx -> ceil(position)
            available.y > flingThresholdPx -> floor(position)
            else -> round(position)
        }.toInt().coerceIn(0, pager.pageCount - 1)
        scope.launch { pager.animateScrollToPage(target, animationSpec = PageSpec) }
        return available
    }
}

/** 왼쪽 레일: 항목 최소 52, 글자 15/1.3, 어절·가운뎃점 뒤에서만 줄바꿈. 선택 = 왼쪽 3 막대 + 700. */
@Composable
private fun Rail(groups: List<CategoryGroup>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalWLColors.current
    val railScroll = rememberScrollState()
    val requesters = remember(groups.size) { List(groups.size) { BringIntoViewRequester() } }
    val heights = remember(groups.size) { IntArray(groups.size) }
    val clearancePx = with(LocalDensity.current) { tabBarClearance().toPx() }
    LaunchedEffect(selected, groups.size) {
        requesters.getOrNull(selected)?.bringIntoView(Rect(0f, 0f, 1f, heights[selected] + clearancePx))
    }
    Column(
        Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .verticalScroll(railScroll)
            .padding(top = WishlistTokens.Space.s8, bottom = tabBarClearance()),
    ) {
        groups.forEachIndexed { i, group ->
            val on = i == selected
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .bringIntoViewRequester(requesters[i])
                    .onSizeChanged { heights[i] = it.height }
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .drawBehind { if (on) drawRect(c.text, size = Size(3.dp.toPx(), size.height)) }
                    .padding(start = 20.dp, end = 10.dp, top = WishlistTokens.Space.s8, bottom = WishlistTokens.Space.s8),
                contentAlignment = Alignment.CenterStart,
            ) {
                WLText(
                    keepAllBreaks(group.name),
                    WLType.body.copy(fontSize = 15.sp, lineHeight = 1.3.em, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) c.text else c.textSecondary,
                )
            }
        }
    }
}

/** 페이지: 상위 이름(최소 44, 13/500 보조색) + 8 + 칩(이름 + 개수) + 점선 `+ 추가`. */
@Composable
private fun GroupSection(
    group: CategoryGroup,
    onChip: (CategoryGroup, app.wishlist.shared.model.CategoryEntry) -> Unit,
    onAdd: (CategoryGroup) -> Unit,
) {
    val c = LocalWLColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
            WLText(group.name, CategoryCaption, color = c.textSecondary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
        ) {
            group.categories.forEach { entry -> WLChip(entry.name, onClick = { onChip(group, entry) }, count = entry.itemCount) }
            WLAddChip(stringResource(R.string.category_add_chip), onClick = { onAdd(group) })
        }
    }
}
