package app.wishlist.android.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.overlay.InputBlocker
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 화면이 `push`·`pop`을 부르기 위한 접근. */
val LocalWLNavigator = staticCompositionLocalOf<WLNavigator> { error("WLNavHost 밖에서 LocalWLNavigator를 읽었다") }

/** 경로 하나를 그린다. `sourceKey`는 이 화면을 연 요소의 키(탭 첫 화면은 null). */
typealias WLRouteContent = @Composable (route: WLRoute, sourceKey: String?) -> Unit

/**
 * 직접 그린 탭 셸. Navigation Compose의 기본 전환·시스템 탭을 쓰지 않는다.
 *
 * - 탭 전환: 페이드 스루(이전 90 ease-in → 새 탭 210 fade-in + scale .97, 90 지연). 탭별 상태는 SaveableStateHolder.
 * - 화면 이동: `SharedTransitionLayout` + 탭마다 `SeekableTransitionState`로 움직이는 `AnimatedContent`.
 *   사진 있음은 `wlSharedPhoto`, 사진 없음은 `WLSharedSurfaceSource`/`WLSurfaceScreen`(자리 표시 면).
 * - 뒤로: `PredictiveBackHandler`. 끄는 동안 같은 pop 전환을 진행값만큼 되감고, 놓을 때 50% 이상이거나 빠르게 놓았으면
 *   나머지를 재생, 아니면 되돌린다. 진행값 없이 끝나는 뒤로(API 33 미만, 3버튼 내비게이션, 화면의 뒤로 버튼)는 같은 pop
 *   전환을 처음부터 재생한다.
 * - 전환 중에는 화면 전체 입력을 막고, 뒤로는 받아서 버린다(시스템에 넘기지 않는다).
 */
@Composable
fun WLNavHost(
    modifier: Modifier = Modifier,
    navigator: WLNavigator = LocalWLNavigator.current,
    content: WLRouteContent,
) {
    val c = LocalWLColors.current
    val currentTab = navigator.currentTab
    val tabHolder = rememberSaveableStateHolder()
    val tabBarAlpha = remember { mutableStateOf<State<Float>?>(null) }

    val tabTransition = updateTransition(currentTab, label = "tab")
    LaunchedEffect(currentTab) {
        // push·pop과 같은 방식: 탭이 바뀔 때마다 자기 탭 전환만 끝낸다. 애니메이션 배율 0처럼 전환이 같은 프레임에 끝나
        // "덜 끝남" 상태를 한 번도 볼 수 없어도 첫 검사에서 바로 끝내고, 끊겨도 finally에서 반드시 끝낸다.
        val tab = currentTab
        val mine = navigator.activeTransition?.takeIf { it is WLNavTransition.Tab && it.tab == tab }
        try {
            snapshotFlow { tabTransition.currentState == tab && tabTransition.targetState == tab && !tabTransition.isRunning }
                .first { it }
        } finally {
            if (mine != null && navigator.activeTransition === mine) navigator.finishTransition()
        }
    }
    // 탭 바 투명도는 매 프레임 바뀌므로 그리기 단계(graphicsLayer)에서만 읽는다. 보일지 여부만 derivedStateOf로 다시 그린다.
    val tabBarAlphaNow = {
        tabBarAlpha.value?.value ?: if (navigator.entries(navigator.currentTab).last().route.showsTabBar) 1f else 0f
    }
    val tabBarVisible by remember(navigator) {
        tabBarVisibility(navigator, tabBarAlpha)
    }

    CompositionLocalProvider(LocalWLNavigator provides navigator) {
        SharedTransitionLayout(modifier.fillMaxSize().background(c.background)) {
            CompositionLocalProvider(LocalWLSharedScope provides this) {
                tabTransition.AnimatedContent(
                    transitionSpec = { tabFadeThrough() },
                    contentKey = { it },
                    modifier = Modifier.fillMaxSize(),
                ) { tab ->
                    tabHolder.SaveableStateProvider(tab.name) {
                        TabStack(tab, isCurrent = tab == currentTab, navigator, tabBarAlpha, content)
                    }
                }
                if (tabBarVisible) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        WLTabBar(
                            current = currentTab,
                            onSelect = { navigator.selectTab(it) },
                            modifier = Modifier.graphicsLayer { this.alpha = tabBarAlphaNow() },
                        )
                    }
                }
                if (navigator.isTransitioning) InputBlocker()
            }
        }
    }
}

private fun tabFadeThrough(): ContentTransform {
    val incoming = tween<Float>(Motion.tabIncoming, delayMillis = Motion.tabIncomingDelay, easing = Curve.fadeIn)
    return (fadeIn(incoming) + scaleIn(incoming, initialScale = Motion.tabIncomingScale)) togetherWith
        fadeOut(tween(Motion.tabOutgoing, easing = Curve.easeIn))
}

/** push = 새 칸이 위(id가 더 큼). 상세(위 칸) 쪽의 이동 방식으로 고른다. */
private fun stackTransform(initial: WLBackStackEntry, target: WLBackStackEntry): ContentTransform {
    val push = target.id > initial.id
    val upper = if (push) target else initial
    return when (upper.route.pushStyle) {
        // 목록은 움직이지 않고 그대로, 상세가 위에서 420 ease-out으로 나타난다. 뒤로: 상세가 250 ease-in으로 사라진다.
        WLPushStyle.Photo ->
            if (push) {
                fadeIn(tween(Motion.pushPhotoContent, easing = Curve.easeOut)) togetherWith ExitTransition.None
            } else {
                EnterTransition.None togetherWith fadeOut(tween(Motion.pushPhotoBackContent, easing = Curve.easeIn))
            }
        // 내용 페이드는 WLSurfaceScreen이 자리 표시 면 위에서 직접 한다.
        WLPushStyle.Surface -> EnterTransition.None togetherWith ExitTransition.None
    }.apply { targetContentZIndex = if (push) 1f else -1f }
}

/** 탭 바 투명도: 다음 화면 내용과 함께 사라지고 나타난다(내용 페이드와 같은 시간표). */
private fun tabBarSpec(initial: WLBackStackEntry, target: WLBackStackEntry) = run {
    val push = target.id > initial.id
    val upper = if (push) target else initial
    when (upper.route.pushStyle) {
        WLPushStyle.Photo ->
            if (push) tween(Motion.pushPhotoContent, easing = Curve.easeOut) else tween<Float>(Motion.pushPhotoBackContent, easing = Curve.easeIn)
        WLPushStyle.Surface ->
            if (push) {
                tween(Motion.pushSurfaceContent, delayMillis = Motion.pushSurfaceLift + Motion.pushSurfaceContentDelay, easing = Curve.easeOut)
            } else {
                tween(Motion.pushSurfaceBackContent, easing = Curve.easeIn)
            }
    }
}

/** 현재 탭을 다시 누르면 이 탭 첫 화면을 맨 위로 부드럽게 스크롤한다(motion.md 머리 접기의 부드러운 스크롤 값). */
@Composable
fun WLScrollToTopEffect(tab: WLTab, scroll: ScrollState) {
    val navigator = LocalWLNavigator.current
    LaunchedEffect(navigator, tab, scroll) {
        navigator.scrollToTopRequests.collect {
            if (it == tab) scroll.animateScrollTo(0, tween(Motion.headerCollapseScroll, easing = Curve.emphasized))
        }
    }
}

/** 끌어서 뒤로를 "빠르게 놓음"으로 보는 진행 속도(초당). 구현 기본값. */
private const val FlingProgressPerSecond = 1.5f

@Composable
private fun SharedTransitionScope.TabStack(
    tab: WLTab,
    isCurrent: Boolean,
    navigator: WLNavigator,
    tabBarAlpha: MutableState<State<Float>?>,
    content: WLRouteContent,
) {
    val entries = navigator.entries(tab)
    val top = entries.last()
    val seek = remember { SeekableTransitionState(top) }
    val transition = rememberTransition(seek, label = "stack-$tab")
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    var known by rememberSaveable { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(top) {
        // 이 효과가 맡은 전환(이 탭의 Push·Pop)만 끝낸다. 애니메이션이 끊겨도(취소·예외) finally에서 반드시 끝내
        // 입력이 영구히 막히지 않게 한다. 같은 전환 객체일 때만 끝내므로 두 번 끝내거나 다음 전환을 끝내지 않는다.
        val mine = navigator.activeTransition?.takeIf { (it is WLNavTransition.Push || it is WLNavTransition.Pop) && it.tab == tab }
        try {
            seek.animateTo(top)
        } finally {
            if (mine != null && navigator.activeTransition === mine) navigator.finishTransition()
        }
        // pop된 칸의 저장 상태를 지운다.
        val alive = navigator.entries(tab).map { it.id }.toSet()
        (known - alive).forEach { holder.removeState(it) }
        known = known.filter { it in alive }
    }

    val alpha = transition.animateFloat(
        transitionSpec = { tabBarSpec(initialState, targetState) },
        label = "tabBar-$tab",
    ) { if (it.route.showsTabBar) 1f else 0f }
    if (isCurrent) SideEffect { tabBarAlpha.value = alpha }

    // overlay가 떠 있는 동안에는 끈다. overlay의 BackHandler와 등록 순서를 다투지 않는다(overlay 아래에서 이 탭 스택이
    // 새로 그려져 나중에 등록되어도 뒤로가 overlay를 건너뛰고 화면을 pop하지 않는다).
    val overlayShowing = LocalOverlayHostState.current.isShowing
    PredictiveBackHandler(enabled = navBackEnabled(isCurrent, overlayShowing, navigator.canPop(), navigator.isTransitioning)) { events ->
        if (!navigator.beginBackGesture()) {
            // 전환 중 뒤로: 받아서 버린다.
            events.collect { }
            return@PredictiveBackHandler
        }
        val from = navigator.entries(tab).last()
        val to = navigator.entries(tab).let { it[it.lastIndex - 1] }
        var last = 0f
        var seen = false
        var velocity = 0f
        var lastTime = 0L
        try {
            events.collect { e ->
                val now = System.nanoTime()
                if (seen && now > lastTime) velocity = (e.progress - last) / ((now - lastTime) / 1e9f)
                seen = true
                last = e.progress
                lastTime = now
                seek.seekTo(e.progress.coerceIn(0f, 1f), to)
            }
            val commit = !seen || last >= Motion.interactiveBackCommitProgress || velocity >= FlingProgressPerSecond
            if (commit) {
                navigator.commitBackGesture() // top이 바뀌면 LaunchedEffect(top)이 남은 전환을 재생하고 끝낸다.
            } else {
                scope.launch { revert(seek, from, to, last, navigator) }
            }
        } catch (e: CancellationException) {
            scope.launch { revert(seek, from, to, last, navigator) }
            throw e
        }
    }

    transition.AnimatedContent(
        transitionSpec = { stackTransform(initialState, targetState) },
        contentKey = { it.id },
        modifier = Modifier.fillMaxSize(),
    ) { entry ->
        SideEffect { if (entry.id !in known) known = known + entry.id }
        holder.SaveableStateProvider(entry.id) {
            CompositionLocalProvider(LocalWLStackScope provides this) {
                content(entry.route, entry.sourceKey)
            }
        }
    }
}

/** 끌어서 뒤로 취소: 같은 pop 전환을 0까지 되감고(emphasized) 원래 화면으로 고정한다. */
private suspend fun revert(
    seek: SeekableTransitionState<WLBackStackEntry>,
    from: WLBackStackEntry,
    to: WLBackStackEntry,
    progress: Float,
    navigator: WLNavigator,
) {
    try {
        if (seek.targetState == to && progress > 0f) {
            coroutineScope {
                val a = Animatable(progress)
                val follower = launch { snapshotFlow { a.value }.collect { seek.seekTo(it.coerceIn(0f, 1f), to) } }
                // 되돌림 길이 = 그 화면 종류(사진·면)의 뒤로 시간 × 남은 비율.
                val back = if (from.route.pushStyle == WLPushStyle.Surface) Motion.pushSurfaceBack else Motion.pushPhotoBack
                val ms = (back * progress).toInt().coerceAtLeast(RevertMinMillis)
                a.animateTo(0f, tween(ms, easing = Curve.emphasized))
                follower.cancel()
            }
        }
        seek.snapTo(from)
    } finally {
        // 되돌림이 끊겨도 끌기 상태를 반드시 풀어 입력이 막힌 채 남지 않게 한다(BackGesture일 때만 풀린다).
        navigator.cancelBackGesture()
    }
}

/** 라우터의 뒤로 처리를 켤지. 현재 탭이고, overlay가 없고, pop할 칸이 있거나 전환 중(받아서 버림)일 때만. */
internal fun navBackEnabled(isCurrent: Boolean, overlayShowing: Boolean, canPop: Boolean, transitioning: Boolean): Boolean =
    isCurrent && !overlayShowing && (canPop || transitioning)

/** 되돌림 최소 시간. 거의 끌지 않았을 때 튀어 보이지 않게 하는 구현 기본값(디자인 값 아님). */
private const val RevertMinMillis = 120

/** Reads the current tab from snapshot state inside the retained derived calculation. */
internal fun tabBarVisibility(navigator: WLNavigator, alpha: State<State<Float>?>): State<Boolean> =
    derivedStateOf {
        val shows = navigator.entries(navigator.currentTab).last().route.showsTabBar
        shows || (alpha.value?.value ?: if (shows) 1f else 0f) > 0f
    }
