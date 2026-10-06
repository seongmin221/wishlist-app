package app.wishlist.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 스택의 한 칸. `id`는 앱 안에서 유일하고 늘어나기만 한다(화면 상태 저장 키, push·pop 방향 판단에 쓴다). */
internal data class WLBackStackEntry(val id: Long, val route: WLRoute, val sourceKey: String?)

/** 진행 중인 전환. 끝나면 `WLNavHost`가 `finishTransition()`을 부른다. */
internal sealed interface WLNavTransition {
    val tab: WLTab

    data class Push(override val tab: WLTab) : WLNavTransition
    data class Pop(override val tab: WLTab) : WLNavTransition
    data class Tab(val from: WLTab, override val tab: WLTab) : WLNavTransition

    /** 손가락으로 끄는 중(predictive back). 놓으면 `Pop`이 되거나 취소된다. */
    data class BackGesture(override val tab: WLTab) : WLNavTransition
}

/**
 * 탭별로 독립된 화면 스택과 현재 탭을 가진다. Compose snapshot 상태를 사용하며 단위 테스트로 전환 규칙을 검증한다.
 *
 * 전환 규칙(motion.md 구현 기본값 "전환 중 입력"):
 * - `push`·`pop`·`selectTab`(다른 탭)은 전환을 시작하고(`isTransitioning = true`) 바로 상태를 바꾼다.
 *   화면 쪽이 모션을 끝내면 `finishTransition()`을 부른다.
 * - 전환 중에는 `push`·`pop`·`selectTab`·`beginBackGesture`를 모두 무시한다(false). 공유 요소 전환 중 탭을 누르거나
 *   뒤로 가도 자리 표시 면이 남지 않는다.
 * - 현재 탭을 다시 고르면 전환 없이 `scrollToTopRequests`로 그 탭을 내보낸다.
 * - 끌어서 뒤로: `beginBackGesture` → (`commitBackGesture` → 모션 끝에 `finishTransition`) 또는 `cancelBackGesture`.
 */
class WLNavigator(initialTab: WLTab = WLTab.Home) {
    private var nextId = 0L
    private val stacks: Map<WLTab, MutableList<WLBackStackEntry>> =
        WLTab.entries.associateWith { mutableStateListOf(WLBackStackEntry(nextId++, WLRoute.TabRoot(it), null)) }

    var currentTab: WLTab by mutableStateOf(initialTab)
        private set

    private val _scrollToTop = MutableSharedFlow<WLTab>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 현재 탭을 다시 눌렀을 때 그 탭. 탭 첫 화면이 맨 위로 부드럽게 스크롤한다. */
    val scrollToTopRequests: SharedFlow<WLTab> = _scrollToTop.asSharedFlow()

    internal var activeTransition: WLNavTransition? by mutableStateOf(null)
        private set

    val isTransitioning: Boolean get() = activeTransition != null

    fun stack(tab: WLTab): List<WLRoute> = stacks.getValue(tab).map { it.route }

    internal fun entries(tab: WLTab): List<WLBackStackEntry> = stacks.getValue(tab)

    fun selectTab(tab: WLTab): Boolean {
        if (isTransitioning) return false
        if (tab == currentTab) {
            _scrollToTop.tryEmit(tab)
            return true
        }
        activeTransition = WLNavTransition.Tab(currentTab, tab)
        currentTab = tab
        return true
    }

    /** `sourceKey`는 누른 요소의 공유 요소 키(`SharedTransitionKeys`). 다음 화면이 같은 키로 이어 받는다. */
    fun push(route: WLRoute, sourceKey: String): Boolean {
        if (isTransitioning) return false
        val tab = currentTab
        activeTransition = WLNavTransition.Push(tab)
        stacks.getValue(tab).add(WLBackStackEntry(nextId++, route, sourceKey))
        return true
    }

    /** 맨 위 화면을 닫는다. 탭 첫 화면이거나 전환 중이면 false(첫 화면이면 뒤로를 시스템에 넘긴다). */
    fun pop(): Boolean {
        if (isTransitioning) return false
        val stack = stacks.getValue(currentTab)
        if (stack.size <= 1) return false
        activeTransition = WLNavTransition.Pop(currentTab)
        stack.removeAt(stack.lastIndex)
        return true
    }

    internal fun finishTransition() {
        activeTransition = null
    }

    /** Only settled navigation identity is saved; animation and surface probes are transient. */
    internal fun save(codec: WLRouteCodec): ArrayList<Any> = arrayListOf(
        currentTab.name,
        nextId,
        ArrayList(WLTab.entries.map { tab ->
            ArrayList(stacks.getValue(tab).map { entry ->
                val tokens = if (entry.route is WLRoute.TabRoot) {
                    listOf("root", entry.route.tab.name)
                } else {
                    checkNotNull(codec.encode(entry.route)) { "Route codec missing for ${entry.route}" }
                }
                arrayListOf<Any>(entry.id, entry.sourceKey.orEmpty(), ArrayList(tokens))
            })
        }),
    )

    companion object {
        internal fun restore(saved: List<Any>, codec: WLRouteCodec): WLNavigator {
            val navigator = WLNavigator(WLTab.valueOf(saved[0] as String))
            val encodedStacks = saved[2] as List<*>
            WLTab.entries.forEachIndexed { index, tab ->
                val entries = encodedStacks[index] as List<*>
                val restored = entries.map { encoded ->
                    val row = encoded as List<*>
                    val tokens = (row[2] as List<*>).map { it as String }
                    val route = if (tokens.firstOrNull() == "root") WLRoute.TabRoot(WLTab.valueOf(tokens[1]))
                    else checkNotNull(codec.decode(tokens)) { "Cannot restore route $tokens" }
                    WLBackStackEntry((row[0] as Number).toLong(), route, (row[1] as String).takeIf { it.isNotEmpty() })
                }
                require(restored.firstOrNull()?.route == WLRoute.TabRoot(tab)) { "Missing tab root" }
                navigator.stacks.getValue(tab).apply { clear(); addAll(restored) }
            }
            val highestId = navigator.stacks.values.flatten().maxOf { it.id }
            navigator.nextId = maxOf((saved[1] as Number).toLong(), highestId + 1)
            return navigator
        }
    }

    internal fun canPop(): Boolean = stacks.getValue(currentTab).size > 1

    internal fun beginBackGesture(): Boolean {
        if (isTransitioning || !canPop()) return false
        activeTransition = WLNavTransition.BackGesture(currentTab)
        return true
    }

    /** 끌어서 뒤로를 확정한다. 스택에서 빼고, 남은 뒤로 모션이 끝나면 `finishTransition()`. */
    internal fun commitBackGesture() {
        val gesture = activeTransition as? WLNavTransition.BackGesture ?: return
        activeTransition = WLNavTransition.Pop(gesture.tab)
        val stack = stacks.getValue(gesture.tab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** 끌어서 뒤로를 취소한다(되돌림 모션이 끝난 뒤 부른다). */
    internal fun cancelBackGesture() {
        if (activeTransition is WLNavTransition.BackGesture) activeTransition = null
    }
}

@Composable
fun rememberWLNavigator(codec: WLRouteCodec): WLNavigator {
    val saver = remember(codec) {
        Saver<WLNavigator, ArrayList<Any>>(save = { it.save(codec) }, restore = { WLNavigator.restore(it, codec) })
    }
    return rememberSaveable(saver = saver) { WLNavigator() }
}
