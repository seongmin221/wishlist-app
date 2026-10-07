package app.wishlist.android.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.wishlist.android.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 머리 위 시트가 멈추는 높이. 끌어서 가는 높이는 이 둘뿐이다(편집처럼 머리가 길어지면 `Resting` 자체가 내려간다). */
enum class WLSheetDetent { Resting, Expanded }

/** 스냅·손잡이 토글: 300 `emphasized`(구현 기본값, motion.md 머리 접기 스크롤과 같은 값). */
private val SnapSpec: AnimationSpec<Float> = tween(Motion.headerCollapseScroll, easing = Curve.emphasized)

/** 머리 높이가 바뀌어 resting이 움직일 때(편집 시작·끝): 260 `ease`(FPurposeEditInPlaceL 머리 펼침과 같은 값). */
private const val RestingMoveMillis = 260

/** resting 아래로 끌 때 손가락 대비 시트가 움직이는 비율(고무줄, 구현 기본값). */
internal const val SheetRubberFactor = 0.35f

/** resting 아래로 늘어나는 최대 거리(구현 기본값). */
private val SheetMaxOvershoot = 96.dp

/** 이 속도(dp/s) 이상으로 놓으면 위치와 상관없이 그 방향 높이로 스냅한다(구현 기본값). */
private val SheetFlingVelocity = 400.dp

/**
 * 머리 위에 얹힌 바텀시트(목적 상세·아카이브 상세)의 위치 상태. 위치는 컨테이너 위 끝에서 시트 윗변까지(px)다.
 * - `expandedTop`: 위쪽 작은 바 바로 아래(호출하는 쪽이 정한다).
 * - `restingTop`: 머리 슬롯의 측정 높이(머리가 길어지면 따라 내려간다). 고정 숫자가 아니다.
 * - `progress`: 0 = resting, 1 = expanded. 머리 슬롯이 이 값으로 모습을 연속으로 바꾼다.
 * 끌기·스냅 판단은 상태 안의 순수 함수(`dragBy`, `settleTarget`)라 단위 테스트한다.
 */
@Stable
class WLHeaderSheetState(initialDetent: WLSheetDetent = WLSheetDetent.Resting) {
    /** 마지막으로 멈춘(또는 멈추러 가는) 높이. 저장·복원 대상이다. */
    var detent by mutableStateOf(initialDetent)
        private set

    /** 시트 윗변 위치(px). 첫 배치 전에는 NaN이다. */
    internal var offset by mutableFloatStateOf(Float.NaN)

    internal var expandedPx by mutableFloatStateOf(0f)
    internal var restingPx by mutableFloatStateOf(0f)
    internal var maxOvershootPx = 0f
    internal var flingVelocityPx = 0f
    private var animJob: Job? = null

    /** 0 = resting, 1 = expanded. 고무줄로 resting 아래에 있어도 0이다. */
    val progress: Float
        get() {
            val range = restingPx - expandedPx
            if (offset.isNaN() || range <= 0f) return if (detent == WLSheetDetent.Expanded) 1f else 0f
            return ((restingPx - offset) / range).coerceIn(0f, 1f)
        }

    private fun positionOf(d: WLSheetDetent) = if (d == WLSheetDetent.Expanded) expandedPx else restingPx

    /**
     * 손가락 이동 `delta`(px, 아래 +)만큼 시트를 옮기고 실제로 옮긴 양을 돌려준다. expanded 위로는 가지 않고,
     * resting 아래로는 고무줄 비율로만 늘어난다. 진행 중인 스냅은 끊는다(손가락이 이긴다).
     */
    internal fun dragBy(delta: Float): Float {
        animJob?.cancel()
        val old = if (offset.isNaN()) positionOf(detent) else offset
        var target = old + delta
        if (delta > 0f && target > restingPx) {
            val free = (restingPx - old).coerceAtLeast(0f)
            target = (old + free + (delta - free) * SheetRubberFactor).coerceAtMost(restingPx + maxOvershootPx)
        }
        target = target.coerceAtLeast(expandedPx)
        offset = target
        // 끌어서 expanded에 닿으면 놓기(플링) 판단 없이도 멈춘 높이가 expanded다(목록 스크롤로 이어지는 경우).
        if (target <= expandedPx) detent = WLSheetDetent.Expanded
        return target - old
    }

    /** 놓을 때 갈 높이: 속도(px/s, 아래 +)가 기준 이상이면 그 방향, 아니면 가까운 쪽. */
    internal fun settleTarget(velocity: Float): WLSheetDetent = when {
        velocity <= -flingVelocityPx -> WLSheetDetent.Expanded
        velocity >= flingVelocityPx -> WLSheetDetent.Resting
        offset < (expandedPx + restingPx) / 2f -> WLSheetDetent.Expanded
        else -> WLSheetDetent.Resting
    }

    /** 손잡이 누르기: 지금 위치가 expanded 쪽이면 resting으로, 아니면 expanded로. */
    suspend fun toggle() = animateTo(if (progress >= 0.5f) WLSheetDetent.Resting else WLSheetDetent.Expanded)

    /** 위치·속도로 가까운 높이에 스냅한다. */
    internal suspend fun settle(velocity: Float) = animateTo(settleTarget(velocity))

    /** `target` 높이로 움직인다(손잡이 토글, 편집 시작 때 resting으로 내려오기 등). 손가락이 닿으면 끊긴다. */
    suspend fun animateTo(target: WLSheetDetent, spec: AnimationSpec<Float> = SnapSpec) {
        detent = target
        val to = positionOf(target)
        val from = if (offset.isNaN()) to else offset
        if (from == to) {
            offset = to
            return
        }
        coroutineScope {
            animJob = coroutineContext[Job]
            animate(from, to, animationSpec = spec) { v, _ -> offset = v }
        }
    }

    /**
     * 높이 기준이 바뀌었을 때(첫 배치·머리 높이 변화·화면 크기 변화). 시트가 resting에 있었다면 새 resting으로 함께 움직인다
     * (`animate = true`면 260 `ease`, 첫 배치는 바로). expanded에 있으면 그대로 둔다.
     */
    internal suspend fun updateAnchors(expanded: Float, resting: Float, animate: Boolean) {
        val restingMoved = resting != restingPx
        expandedPx = expanded
        restingPx = resting
        when {
            offset.isNaN() || !animate -> offset = positionOf(detent)
            detent == WLSheetDetent.Expanded -> if (animJob?.isActive != true) offset = expanded
            restingMoved -> animateTo(WLSheetDetent.Resting, tween(RestingMoveMillis, easing = Curve.ease))
        }
    }

    /** 지도 앱 시트처럼 목록 스크롤과 이어지는 연결. */
    internal val nestedScroll = object : NestedScrollConnection {
        // 위로 밀 때: expanded에 닿을 때까지 시트가 먼저 올라가고, 남은 양만 목록이 스크롤한다.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y < 0f && offset > expandedPx) {
                Offset(0f, dragBy(available.y))
            } else {
                Offset.Zero
            }

        // 목록이 맨 위라 다 쓰지 못한 아래 끌기는 시트를 내린다(resting 아래는 고무줄). 남은 양은 모두 시트가 가진다.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            dragBy(available.y)
            return Offset(0f, available.y)
        }

        // 시트가 높이 사이(또는 고무줄)에서 놓이면 목록 플링 대신 시트를 스냅한다.
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset.isNaN() || abs(offset - expandedPx) < 0.5f) return Velocity.Zero
            settle(available.y)
            return available
        }

        // expanded에서 목록을 아래로 플링해 맨 위에 닿고도 속도가 남으면 시트가 내려간다.
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (available.y < flingVelocityPx) return Velocity.Zero
            animateTo(WLSheetDetent.Resting)
            return available
        }
    }

    companion object {
        val Saver: Saver<WLHeaderSheetState, String> = Saver(
            save = { it.detent.name },
            restore = { WLHeaderSheetState(WLSheetDetent.valueOf(it)) },
        )
    }
}

/** 시트 위치를 저장한다(멈춘 높이). 상세에 다녀와도 같은 높이로 돌아온다. */
@Composable
fun rememberWLHeaderSheetState(initialDetent: WLSheetDetent = WLSheetDetent.Resting): WLHeaderSheetState =
    rememberSaveable(saver = WLHeaderSheetState.Saver) { WLHeaderSheetState(initialDetent) }

/**
 * 고정된 머리(뒤 층) 위에 얹혀 오르내리는 바텀시트(앞 층). 목적 상세·아카이브 상세가 쓴다.
 * - 머리 슬롯은 진행값(0 = resting, 1 = expanded)을 받아 모습을 연속으로 바꾼다. 머리 슬롯의 측정 높이가 곧 resting이라
 *   머리가 길어지면(편집) resting이 260 `ease`로 따라 내려간다. 펼친 상태에서 편집을 시작할 때는 `state.animateTo(Resting)`.
 * - `expandedTop`: 펼친 시트 윗변(위쪽 작은 바 바로 아래).
 * - 시트: `sheet` 면, 위 모서리 36, 손잡이 줄 28(누르면 resting ↔ expanded, 끌어도 움직임), 그 아래 `content`가 세로 스크롤한다.
 *   시트를 움직이는 것은 시트(손잡이·목록)뿐이다. 뒤의 고정 머리는 끌기를 받지 않는다.
 *   손잡이 접근성 이름은 리소스("목록 넓게 보기"/"헤더 펼치기", iOS `wl.headerSheet.*`와 같은 값)가 기본이다.
 *   목록을 끌면 시트가 먼저 움직이고(expanded까지), 목록이 맨 위일 때 아래로 끌면 시트가 내려온다. 놓으면 300 `emphasized` 스냅.
 * - 바탕(머리 색)은 호출하는 쪽이 `modifier`로 칠한다. 시트 내용 끝 여백(탭 바 등)도 `content`가 넣는다.
 */
@Composable
fun WLHeaderSheet(
    state: WLHeaderSheetState,
    expandedTop: Dp,
    modifier: Modifier = Modifier,
    expandLabel: String = stringResource(R.string.wl_header_sheet_expand),
    collapseLabel: String = stringResource(R.string.wl_header_sheet_collapse),
    scrollState: ScrollState = rememberScrollState(),
    header: @Composable (progress: () -> Float) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWLColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var headerHeight by remember { mutableIntStateOf(0) }
    val expandedPx = with(density) { expandedTop.toPx() }
    state.maxOvershootPx = with(density) { SheetMaxOvershoot.toPx() }
    state.flingVelocityPx = with(density) { SheetFlingVelocity.toPx() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(expandedPx, headerHeight) {
        if (headerHeight == 0) return@LaunchedEffect
        state.updateAnchors(expandedPx, headerHeight.toFloat().coerceAtLeast(expandedPx), animate = placed)
        placed = true
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sheetHeight = maxHeight - expandedTop
        Box(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }) {
            header { state.progress }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .offset { IntOffset(0, (if (state.offset.isNaN()) headerHeight.toFloat() else state.offset).roundToInt()) }
                .clip(RoundedCornerShape(topStart = WishlistTokens.Radius.xl, topEnd = WishlistTokens.Radius.xl))
                .background(c.sheet)
                .nestedScroll(state.nestedScroll),
        ) {
            val expanded = state.detent == WLSheetDetent.Expanded
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .draggable(
                        rememberDraggableState { state.dragBy(it) },
                        Orientation.Vertical,
                        onDragStopped = { v -> state.settle(v) },
                    )
                    .clickable(role = Role.Button) {
                        val collapsing = state.progress >= 0.5f
                        scope.launch { state.toggle() }
                        // 손잡이로 내릴 때는 목록도 맨 위로(끌어서 내리면 목록이 먼저 맨 위까지 스크롤되는 것과 같은 결과).
                        if (collapsing) scope.launch { scrollState.animateScrollTo(0, SnapSpec) }
                    }
                    .semantics { contentDescription = if (expanded) collapseLabel else expandLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.handle))
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState), content = content)
        }
    }
}
