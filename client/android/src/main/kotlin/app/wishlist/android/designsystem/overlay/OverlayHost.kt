package app.wishlist.android.designsystem.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WishlistTokens

/** 확인창 내용. 확인(`onConfirm`)은 창이 닫히기 시작한 뒤에 한 번만 불린다. */
data class WLDialogSpec(
    val title: String,
    val bullets: List<String>,
    val cancelText: String,
    val confirmText: String,
    val confirmKind: WLButtonKind,
    val onConfirm: () -> Unit,
)

/** 메뉴 항목. 누르면 메뉴가 닫히기 시작하고 `onClick`이 불린다. */
data class WLMenuItem(val text: String, val icon: (@Composable () -> Unit)? = null, val onClick: () -> Unit)

enum class OverlayPhase { Opening, Open, Closing }

internal sealed class OverlayEntry(val id: Long) {
    var phase by mutableStateOf(OverlayPhase.Opening)

    /** 뒤 화면을 흐리고 어둡게 하는 overlay(시트·확인창)인지. 메뉴는 아니다. */
    abstract val needsScrim: Boolean
}

internal class SheetEntry(id: Long, val draggable: Boolean, val content: @Composable () -> Unit) : OverlayEntry(id) {
    override val needsScrim get() = true
}

internal class DialogEntry(id: Long, val spec: WLDialogSpec) : OverlayEntry(id) {
    override val needsScrim get() = true
}

internal class MenuEntry(id: Long, val anchor: Rect, val items: List<WLMenuItem>) : OverlayEntry(id) {
    override val needsScrim get() = false
}

/**
 * overlay(시트·확인창·메뉴)의 상태 기계. 화면 루트의 `OverlayHost` 하나가 이 상태를 그린다.
 *
 * 각 overlay는 `Opening -> Open -> Closing -> (제거)`를 지난다. 화면 쪽 애니메이션이 `Opening`·`Closing`을 끝낼 때
 * `onOpened`·`onClosed`를 부른다. 전환 중(`isAnimating`)의 규칙은 항상 같다.
 *
 * - `dismiss`/`requestDismiss`: 가장 위 overlay가 `Open`일 때만 닫기 시작한다. 열리는 중·닫히는 중이면 무시(false).
 *   뒤로 연타·막 연타가 닫기 모션을 되돌리거나 겹치지 못한다.
 * - `show*`: 열리는 중이면 무시(false). 닫히는 중이면 **하나만 줄 세운다**: 닫기가 끝난 직후 열린다(나중 요청이 앞 요청을 대체).
 *   "메뉴 항목 -> 닫기 -> 확인창 열기"를 호출 순서대로 써도 확인창이 사라지지 않는다. 그 외에는 바로 쌓는다.
 * - 닫기는 항상 가장 위 overlay 하나만 닫는다(확인창이 시트 위에 있으면 확인창만).
 */
class OverlayHostState {
    internal val entries = mutableStateListOf<OverlayEntry>()
    private var nextId = 0L
    private var pending: (() -> Unit)? = null

    val isAnimating: Boolean get() = entries.any { it.phase != OverlayPhase.Open }

    fun showSheet(draggable: Boolean = true, content: @Composable () -> Unit): Boolean =
        push { SheetEntry(it, draggable, content) }

    fun showDialog(spec: WLDialogSpec): Boolean = push { DialogEntry(it, spec) }

    /** `anchor`는 눌린 버튼의 창 기준 사각형(`Modifier.wlAnchor`). */
    fun showMenu(anchor: Rect, items: List<WLMenuItem>): Boolean = push { MenuEntry(it, anchor, items) }

    /** 가장 위 overlay를 닫는다. 시스템 뒤로·막 누르기가 쓴다. 닫기를 시작했으면 true. */
    fun dismiss(): Boolean {
        val top = entries.lastOrNull() ?: return false
        return requestDismiss(top.id)
    }

    internal fun requestDismiss(id: Long): Boolean {
        if (isAnimating) return false
        val top = entries.lastOrNull() ?: return false
        if (top.id != id) return false
        top.phase = OverlayPhase.Closing
        return true
    }

    internal fun onOpened(id: Long) {
        entries.firstOrNull { it.id == id && it.phase == OverlayPhase.Opening }?.phase = OverlayPhase.Open
    }

    internal fun onClosed(id: Long) {
        entries.removeAll { it.id == id }
        if (entries.none { it.phase == OverlayPhase.Closing }) {
            val next = pending
            pending = null
            next?.invoke()
        }
    }

    private fun push(make: (Long) -> OverlayEntry): Boolean {
        if (entries.any { it.phase == OverlayPhase.Opening }) return false
        val add = { entries.add(make(nextId++)); Unit }
        if (entries.any { it.phase == OverlayPhase.Closing }) {
            pending = add
        } else {
            add()
        }
        return true
    }
}

@Composable
fun rememberOverlayHostState(): OverlayHostState = remember { OverlayHostState() }

/** 시트·메뉴·확인창 안쪽에서 `dismiss()`를 부르기 위한 접근. */
val LocalOverlayHostState = compositionLocalOf<OverlayHostState?> { null }

/** 메뉴를 띄우는 버튼에 붙여 `showMenu`의 anchor를 얻는다. */
fun Modifier.wlAnchor(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInWindow()) }

/**
 * 화면 루트에 한 번 둔다. 시스템 Dialog·ModalBottomSheet·Popup을 쓰지 않고 같은 창 안에서 직접 그린다.
 * 시트·확인창이 떠 있는 동안 뒤 콘텐츠에만 블러 12(API 31+)와 어두운 막이 걸린다.
 */
@Composable
fun OverlayHost(state: OverlayHostState, content: @Composable () -> Unit) {
    val colors = LocalWLColors.current
    val scrimWanted = state.entries.any { it.needsScrim && it.phase != OverlayPhase.Closing }
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(scrimWanted) {
        if (scrimWanted) {
            scrim.animateTo(1f, tween(WishlistTokens.Motion.scrimIn, easing = WishlistTokens.Curve.easeOut))
        } else {
            scrim.animateTo(0f, tween(WishlistTokens.Motion.scrimOut, easing = WishlistTokens.Curve.easeIn))
        }
    }
    val scrimActive by remember { derivedStateOf { scrim.value > 0f || state.entries.any { it.needsScrim } } }

    CompositionLocalProvider(LocalOverlayHostState provides state) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().wlBackdropBlur { scrim.value }) { content() }
            if (scrimActive) {
                WLScrim(
                    progress = { scrim.value },
                    color = colors.scrimDim,
                    onTap = {
                        // 막 누르기는 시트만 닫는다. 확인창은 취소·확인 버튼으로만 닫힌다. 전환 중에는 dismiss가 무시한다.
                        if (state.entries.lastOrNull() is SheetEntry) state.dismiss()
                    },
                )
            }
            state.entries.forEachIndexed { index, entry ->
                key(entry.id) {
                    when (entry) {
                        is SheetEntry -> SheetLayer(entry, state)
                        is DialogEntry -> DialogLayer(entry, state, dimBelow = index > 0)
                        is MenuEntry -> MenuLayer(entry, state)
                    }
                }
            }
            // 전환 중에는 overlay 안쪽(시트 내용·메뉴 항목·확인창 버튼)도 입력을 받지 않는다(motion.md 구현 기본값).
            if (state.isAnimating) InputBlocker()
        }
        // content 뒤에 등록해 content 안의 뒤로 처리(내비게이션)보다 우선한다. overlay가 새로 생길 때마다 가장 나중 등록이 된다.
        if (state.entries.isNotEmpty()) BackHandler { state.dismiss() }
    }
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
