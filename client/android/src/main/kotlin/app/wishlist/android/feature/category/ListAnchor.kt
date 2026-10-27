package app.wishlist.android.feature.category

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import app.wishlist.shared.presentation.AnchorRestore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * C7 목록 anchor (pure part, JVM-tested). The lists are a 2-column masonry inside one vertical scroll,
 * so "first visible card" is computed from card boxes in content coordinates instead of a lazy list index.
 */
object ListAnchor {
    /** A laid-out card's vertical extent in the scroll content (px). */
    data class CardBox(val id: String, val top: Int, val bottom: Int)

    /** A card and how far (px) the viewport top is inside it. */
    data class Position(val itemId: String, val offset: Int)

    /**
     * The card crossing (or first below) the viewport's top edge that starts highest; list order breaks
     * ties (the left column first). Its offset is how far the top edge is inside it (0 when below).
     */
    fun firstVisible(cards: List<CardBox>, viewportTop: Int): Position? =
        cards.filter { it.bottom > viewportTop }.minByOrNull { it.top }
            ?.let { Position(it.id, (viewportTop - it.top).coerceAtLeast(0)) }

    /** Scroll value that puts the resolved card first: its top, plus the kept offset when it is the same card. */
    fun scrollTarget(cardTop: Int, kept: Position?, resolvedItemId: String, sameItem: Boolean): Int =
        cardTop + if (sameItem && kept?.itemId == resolvedItemId) kept.offset else 0

    /**
     * Asks for newer cards once per visit to the top edge (Ruling 9, same as iOS): it fires when the user is at
     * the top ([userAtEdge]: a user scroll, or a list that cannot scroll) with newer cards and nothing in flight,
     * and re-arms only after the list left the top. A failed read therefore waits for the next visit, not a loop.
     */
    class TopEdgeTrigger {
        private var armed = true

        /** True when [ListAnchorEffects] should call loadNewer now. */
        fun update(scrollValue: Int, userAtEdge: Boolean, hasNewer: Boolean, busy: Boolean): Boolean {
            if (scrollValue > 0) {
                armed = true
                return false
            }
            if (!armed || !userAtEdge || !hasNewer || busy) return false
            armed = false
            return true
        }
    }

    /** A restore that resolved no card: the top (category list), or no scroll at all ([keepPosition], purpose detail — Ruling 8). */
    fun nullRestoreTarget(keepPosition: Boolean): Int? = if (keepPosition) null else 0
}

/**
 * Where each laid-out card of a grid sits in its scroll content. The grid reports cards with
 * [anchorCard]; skeleton cards are not reported (their height is not final).
 */
@Stable
internal class ListAnchorTracker {
    private var content: LayoutCoordinates? = null
    private val boxes = mutableStateMapOf<String, ListAnchor.CardBox>()
    private val order = mutableListOf<String>()

    /** On the scrolled content (inside `verticalScroll`), so positions do not depend on the scroll value. */
    val contentModifier: Modifier = Modifier.onPlaced { content = it }

    /** The first visible card at [scrollValue], or null before any card is laid out. */
    fun firstVisible(scrollValue: Int): ListAnchor.Position? =
        ListAnchor.firstVisible(order.mapNotNull { boxes[it] }, scrollValue)

    fun topOf(id: String): Int? = boxes[id]?.top

    internal fun place(id: String, coordinates: LayoutCoordinates) {
        val root = content ?: return
        if (!root.isAttached || !coordinates.isAttached) return
        val top = root.localPositionOf(coordinates, Offset.Zero).y.toInt()
        if (id !in order) order += id
        boxes[id] = ListAnchor.CardBox(id, top, top + coordinates.size.height)
    }

    internal fun remove(id: String) {
        boxes.remove(id)
        order.remove(id)
    }

    /** Keeps list order for ties (the masonry places cards in this order). */
    internal fun reorder(ids: List<String>) {
        val known = order.toSet()
        order.clear()
        order += ids.filter { it in known }
    }
}

/** Reports this laid-out card to [tracker] while it is composed. */
@Composable
internal fun Modifier.anchorCard(tracker: ListAnchorTracker?, id: String): Modifier {
    if (tracker == null) return this
    DisposableEffect(tracker, id) { onDispose { tracker.remove(id) } }
    return this.onPlaced { tracker.place(id, it) }
}

/** How long a restore waits for its card's page to lay out (the photo planner gives up at 3 s). */
private const val RESTORE_WAIT_MS = 4_000L

/**
 * One list screen's anchor wiring: [tracker] knows the laid-out cards, [capture] reads the first visible
 * card before a refresh/foreground/loadNewer (its id goes to the Presenter, its offset is kept for the restore).
 */
@Stable
internal class ListAnchorController(val tracker: ListAnchorTracker, val scroll: ScrollState) {
    var kept: ListAnchor.Position? = null
        private set

    /** The first visible card's id (null: none laid out yet), remembered with its offset. */
    fun capture(): String? {
        kept = tracker.firstVisible(scroll.value)
        return kept?.itemId
    }

    /** Uses a position read earlier (the screen left composition since) for the next restore. */
    fun keep(position: ListAnchor.Position?): String? {
        kept = position
        return position?.itemId
    }

    /** Scrolls so [id] is the first card, [offset] px inside it, once its page is laid out. */
    suspend fun scrollToCard(id: String, offset: (Int) -> Int) {
        // Two frames: the grid's own effect swaps its layout for the new list before cards are read.
        withFrameNanos { }
        withFrameNanos { }
        val top = withTimeoutOrNull(RESTORE_WAIT_MS) {
            snapshotFlow { tracker.topOf(id) }.filterNotNull().distinctUntilChanged().first()
        } ?: return
        withFrameNanos { } // the scroll range includes the laid-out page
        scroll.scrollTo(offset(top))
    }
}

/**
 * - Applies each new [restore] token once ([applied]/[onApplied] live in the screen's owner, so a screen
 *   coming back does not apply an old token again): the resolved card first (its kept offset when it is
 *   the same card), or the top when nothing resolved.
 * - At the top edge (a user scroll reaching it, or a list that does not scroll) with [hasNewer]: [loadNewer] once
 *   per visit ([ListAnchor.TopEdgeTrigger]), then keeps the card that was first in place once the newer page landed.
 * - [keepPositionOnNullRestore]: a restore that resolved no card leaves the scroll as it is (purpose detail).
 */
@Composable
internal fun ListAnchorEffects(
    controller: ListAnchorController,
    restore: AnchorRestore?,
    hasNewer: Boolean,
    busy: Boolean,
    prepending: Boolean,
    applied: Long,
    onApplied: (Long) -> Unit,
    loadNewer: () -> Unit,
    keepPositionOnNullRestore: Boolean = false,
) {
    val scroll = controller.scroll
    LaunchedEffect(restore?.token) {
        val r = restore ?: return@LaunchedEffect
        if (r.token == applied) return@LaunchedEffect
        onApplied(r.token)
        val id = r.resolvedItemId
        if (id == null) {
            ListAnchor.nullRestoreTarget(keepPositionOnNullRestore)?.let { scroll.scrollTo(it) }
            return@LaunchedEffect
        }
        controller.scrollToCard(id) { top -> ListAnchor.scrollTarget(top, controller.kept, id, r.sameItem) }
    }
    val latestHasNewer by rememberUpdatedState(hasNewer)
    val latestBusy by rememberUpdatedState(busy)
    val latestLoadNewer by rememberUpdatedState(loadNewer)
    val topEdge = remember(controller) { ListAnchor.TopEdgeTrigger() }
    LaunchedEffect(controller) {
        snapshotFlow {
            TopEdgeInput(scroll.value, scroll.isScrollInProgress || scroll.maxValue == 0, latestHasNewer, latestBusy)
        }.distinctUntilChanged().collect { (value, userAtEdge, newer, busy) ->
            if (topEdge.update(value, userAtEdge, newer, busy)) {
                controller.capture()
                latestLoadNewer()
            }
        }
    }
    var wasPrepending by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(prepending) {
        if (prepending) {
            wasPrepending = true
            return@LaunchedEffect
        }
        if (!wasPrepending) return@LaunchedEffect
        wasPrepending = false
        val kept = controller.kept ?: return@LaunchedEffect
        controller.scrollToCard(kept.itemId) { top -> top + kept.offset }
    }
}

private data class TopEdgeInput(val scrollValue: Int, val userAtEdge: Boolean, val hasNewer: Boolean, val busy: Boolean)
