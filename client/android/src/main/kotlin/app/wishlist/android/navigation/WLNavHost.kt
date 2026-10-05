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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WishlistTokens.Curve
import app.wishlist.android.designsystem.WishlistTokens.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
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
    navigator: WLNavigator = remember { WLNavigator() },
    content: WLRouteContent,
) {
    val c = LocalWLColors.current
    val currentTab by navigator.currentTab.collectAsState()
    val tabHolder = rememberSaveableStateHolder()
    val registry = remember { WLSurfaceRegistry() }
    val tabBarAlpha = remember { mutableStateOf<State<Float>?>(null) }

    val tabTransition = updateTransition(currentTab, label = "tab")
    LaunchedEffect(tabTransition, navigator) {
        snapshotFlow { tabTransition.currentState == tabTransition.targetState && tabTransition.currentState == navigator.currentTab.value }
            .collect { settled ->
                if (settled && navigator.activeTransition is WLNavTransition.Tab) navigator.finishTransition()
            }
    }

    CompositionLocalProvider(LocalWLNavigator provides navigator, LocalWLSurfaceRegistry provides registry) {
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
                val top = navigator.entries(currentTab).last()
                val alpha = tabBarAlpha.value?.value ?: if (top.route.showsTabBar) 1f else 0f
                if (top.route.showsTabBar || alpha > 0f) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        WLTabBar(
                            current = currentTab,
                            onSelect = { navigator.selectTab(it) },
                            modifier = Modifier.graphicsLayer { this.alpha = alpha },
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
    return (fadeIn(incoming) + scaleIn(incoming, initialScale = 0.97f)) togetherWith
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

/** 현재 탭을 다시 누르면 이 탭 첫 화면을 맨 위로 부드럽게 스크롤한다(300 `emphasized`, motion.md 머리 접기와 같은 값). */
@Composable
fun WLScrollToTopEffect(tab: WLTab, scroll: ScrollState) {
    val navigator = LocalWLNavigator.current
    LaunchedEffect(navigator, tab, scroll) {
        navigator.scrollToTopRequests.collect {
            if (it == tab) scroll.animateScrollTo(0, tween(300, easing = Curve.emphasized))
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
    val known = remember { mutableSetOf<Long>() }

    LaunchedEffect(top) {
        seek.animateTo(top)
        val t = navigator.activeTransition
        if ((t is WLNavTransition.Push || t is WLNavTransition.Pop) && t.tab == tab) navigator.finishTransition()
        // pop된 칸의 저장 상태를 지운다.
        val alive = navigator.entries(tab).map { it.id }.toSet()
        (known - alive).forEach { holder.removeState(it) }
        known.retainAll(alive)
    }

    val alpha = transition.animateFloat(
        transitionSpec = { tabBarSpec(initialState, targetState) },
        label = "tabBar-$tab",
    ) { if (it.route.showsTabBar) 1f else 0f }
    if (isCurrent) SideEffect { tabBarAlpha.value = alpha }

    PredictiveBackHandler(enabled = isCurrent && (navigator.canPop() || navigator.isTransitioning)) { events ->
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
        SideEffect { known += entry.id }
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
    if (seek.targetState == to && progress > 0f) {
        coroutineScope {
            val a = Animatable(progress)
            val follower = launch { snapshotFlow { a.value }.collect { seek.seekTo(it.coerceIn(0f, 1f), to) } }
            a.animateTo(0f, tween((Motion.pushPhotoBack * progress).toInt().coerceAtLeast(120), easing = Curve.emphasized))
            follower.cancel()
        }
    }
    seek.snapTo(from)
    navigator.cancelBackGesture()
}

@Composable
private fun InputBlocker() {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
    )
}
