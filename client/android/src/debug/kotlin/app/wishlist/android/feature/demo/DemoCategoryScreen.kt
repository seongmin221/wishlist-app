package app.wishlist.android.feature.demo

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLAddChip
import app.wishlist.android.designsystem.WLChip
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** 레일 폭 124(FCategoryHomeL nav). */
private val RailWidth = 124.dp

/** 페이지 넘김·레일 이동 모션: 300 `emphasized`(구현 기본값, 머리 접기 스크롤과 같은 값). */
private val PageSpec = tween<Float>(Motion.headerCollapseScroll, easing = Curve.emphasized)

/**
 * 카테고리 탭 첫 화면(FCategoryHomeL, 사용자 결정 2026-10-07: 세로 페이징).
 * 오른쪽은 상위 카테고리 하나가 한 페이지인 세로 페이저다(제목 + 세부 유형 칩 + 점선 추가 하나). 손가락을 위로 끌면 다음 상위,
 * 아래로 끌면 이전 상위로 넘어가고 놓으면 가까운 페이지로 스냅한다. 페이지 내용이 넘치면 페이지 안에서 먼저 스크롤한다.
 * - 왼쪽 레일은 현재 페이지를 표시한다. 누르면 그 페이지로 같은 모션으로 이동하고, 이동 중에는 목표를 선택 상태로 고정한다.
 * - 현재 탭 재선택 → 첫 페이지. 현재 페이지는 탭 전환·상세 다녀오기 뒤에도 남는다(`rememberPagerState`는 저장된다).
 * - 칩 → 세부 유형 목록은 가로 밀기.
 */
@Composable
fun DemoCategoryScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val tops = DemoContent.tops
    val pager = rememberPagerState { tops.size }
    // 레일을 눌러 이동 중인 목표 페이지. 그동안 현재 페이지 대신 이 값을 선택으로 쓴다(사이 페이지에서 깜빡이지 않게).
    var anchor by remember { mutableStateOf<Int?>(null) }
    val selected = anchor ?: pager.currentPage
    val scope = rememberCoroutineScope()
    val goTo: (Int) -> Unit = { i ->
        anchor = i
        scope.launch {
            try {
                pager.animateScrollToPage(i, animationSpec = PageSpec)
            } finally {
                // 끝났거나 손가락이 끊었으면 현재 페이지로 돌아간다. 다른 항목을 눌러 바뀐 목표는 지우지 않는다.
                if (anchor == i) anchor = null
            }
        }
    }
    val density = LocalDensity.current
    val overflowToPager = remember(pager, density) { OverflowToPager(pager, scope, with(density) { PageFlingThreshold.toPx() }) }
    // 현재 탭을 다시 누르면 첫 페이지로(WLScrollToTopEffect의 페이저판).
    LaunchedEffect(nav) {
        nav.scrollToTopRequests.collect { if (it == WLTab.Category) goTo(0) }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.padding(start = WishlistTokens.Space.screenMargin, end = WishlistTokens.Space.screenMargin, bottom = WishlistTokens.Space.s16)) {
            DemoTabHeader("카테고리", "상품 ${DemoContent.productTotal}개")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            DemoRail(tops, selected, goTo)
            VerticalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PageSpec),
                key = { tops[it].name },
            ) { page ->
                // 페이지 높이 = 오른쪽 영역. 내용은 위에 붙인다. 넘칠 때만(큰 글자) 페이지 안 스크롤을 켠다. 켜 두면 짧은 페이지에서도
                // 안쪽 스크롤이 끌기를 가져가 페이저가 넘어가지 않는다. 넘치는 페이지는 끝에서 더 끈 만큼을 페이저로 넘긴다.
                val inner = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(overflowToPager)
                        .verticalScroll(inner, enabled = inner.maxValue > 0)
                        .padding(start = WishlistTokens.Space.s8, end = WishlistTokens.Space.s16, top = WishlistTokens.Space.s8, bottom = tabBarClearance()),
                ) {
                    DemoCategorySection(tops[page])
                }
            }
        }
    }
}

/** 넘치는 페이지 끝에서 놓을 때 다음·이전 페이지로 넘기는 속도(구현 기본값). */
private val PageFlingThreshold = 400.dp

/**
 * 넘치는 페이지(큰 글자)의 안쪽 스크롤이 끝에 닿은 뒤 남은 끌기를 페이저로 넘긴다. 페이저가 페이지 사이에 걸쳐 있으면 페이저가 먼저 받는다.
 * 놓을 때 페이지 사이면 속도(기준 이상이면 그 방향)·거리(가까운 쪽)로 스냅한다. foundation 페이저는 같은 방향 자식 스크롤의 남은 양을 받지 않는다.
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

/**
 * 왼쪽 레일(폭 124, 위 8·아래 탭 바 여백, 따로 스크롤). 항목 최소 52, 글자 15/1.3, 어절·가운뎃점 뒤에서만 줄바꿈.
 * 선택: 왼쪽 3 먹색 막대 + 700 + 글자색. 비선택: 보조색. 글자 시작은 둘 다 20.
 */
@Composable
private fun DemoRail(tops: List<DemoTop>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalWLColors.current
    val railScroll = rememberScrollState()
    val requesters = remember(tops.size) { List(tops.size) { BringIntoViewRequester() } }
    val heights = remember(tops.size) { IntArray(tops.size) }
    // 레일 아래쪽은 탭 바가 덮으므로, 항목 아래로 탭 바 여백만큼 더 보이게 요청한다(그냥 bringIntoView면 탭 바 밑에 멈춘다).
    val clearancePx = with(LocalDensity.current) { tabBarClearance().toPx() }
    LaunchedEffect(selected) {
        requesters[selected].bringIntoView(Rect(0f, 0f, 1f, heights[selected] + clearancePx))
    }
    Column(
        Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .verticalScroll(railScroll)
            .padding(top = WishlistTokens.Space.s8, bottom = tabBarClearance()),
    ) {
        tops.forEachIndexed { i, top ->
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
                    keepAllBreaks(top.name),
                    WLType.body.copy(fontSize = 15.sp, lineHeight = 1.3.em, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) c.text else c.textSecondary,
                )
            }
        }
    }
}

/** 섹션: 제목(최소 44, 13/500 보조색) + 8 + 칩들(간격 8) + 점선 추가 칩. */
@Composable
private fun DemoCategorySection(top: DemoTop, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
            WLText(top.name, DemoCaption, color = c.textSecondary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
        ) {
            top.types.forEach { DemoTypeChip(top.name, it, sourceKey = "category/${top.name}/${it.name}") }
            WLAddChip("추가", onClick = {})
        }
    }
}

/** 세부 유형 칩(숫자 포함). 누르면 세부 유형 목록이 오른쪽에서 밀려 들어온다. `sourceKey`는 탭 안에서 유일해야 한다. */
@Composable
internal fun DemoTypeChip(top: String, type: DemoType, sourceKey: String) {
    val nav = LocalWLNavigator.current
    WLChip(type.name, onClick = { nav.push(DemoRoute.CategoryList(top, type.name), sourceKey) }, count = type.count)
}
