package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.HomeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The groups that open a continuous-processing flow; ANALYSIS_IN_PROGRESS has none. */
private val FLOW_GROUPS = setOf(HomeActionGroup.CLASSIFICATION_REVIEW, HomeActionGroup.INFORMATION_COMPLETION)

/** Cards kept ready behind the current one; at this many or fewer left, the next HOME-02 page is read. */
private const val STACK = 2

/** Page size for reading back the cards in front of a window (HOME-02 allows 1~100). */
private const val FRONT_PAGE = 100

/** At most this many read-back pages per window; past that the session's ids stand in for the rest. */
private const val FRONT_PAGES_MAX = 20

/** A window or first page, with the ids in front of it read back page by page (null: not read). */
private class FlowRead(val page: ItemPage, val frontIds: List<String>?)

/**
 * The home continuous-processing queue over HOME-02 (C7, read only). A session is one flow screen's
 * lifetime: [pass] records a temporary pass in memory (C7 saves nothing; C8 replaces it with the
 * decision commands), [restart] forgets this session's passes and reads the first page again, and an
 * account change drops everything.
 *
 * - [start] reads the first page, or with a carousel preview's anchor the window around it: the
 *   newer cards in front of that preview count as already behind this session (not as passes).
 * - [foreground] rebuilds the queue from the window around the current card's anchor. The current
 *   card stays when it is still in the group; otherwise the queue continues from the fallback card,
 *   and a fallback newer than the current card means nothing older is left. Passed and skipped cards
 *   never come back.
 * - `total` = passes + cards left, where cards left = `totalCount −` the items in front of the
 *   current card. A window with a previous cursor has more in front than it shows: those pages are
 *   read back so the count is exact (cards saved elsewhere meanwhile included). A next page keeps the
 *   total (its `totalCount` would count cards saved in front since), and the last page fixes it to
 *   the cards left. Other devices' changes are corrected on the next foreground or start.
 * - Reads happen only on these intents (and the next page near the end), never on a timer. A failed
 *   first read is [HomeFlowPhase.ERROR] ([retry] repeats it); a failed foreground or next-page read
 *   keeps the queue without a notice, and the next signal reads again.
 */
class HomeFlowPresenter internal constructor(
    private val home: HomeRepository,
    session: AuthSession,
    dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val mutable = MutableStateFlow(HomeFlowState())

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<HomeFlowState> = mutable.asStateFlow()

    // Confined to the lane.
    private var group: HomeActionGroup? = null
    private val passedIds = linkedSetOf<String>()
    private val skippedBefore = mutableSetOf<String>()
    private var queue: List<HomeFlowCard> = emptyList()
    private var next: String? = null
    private var remaining = 0
    private var phase = HomeFlowPhase.LOADING
    private var error: ClientError? = null
    private var refreshing = false
    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var retryLast: (() -> Unit)? = null

    /** Opens the flow of [group] (review or completion only), at [startAnchor]'s card when given. Returns at once. */
    fun start(group: HomeActionGroup, startAnchor: String?) {
        intent {
            if (group !in FLOW_GROUPS) return@intent
            reset()
            this.group = group
            initialLoad(startAnchor)
        }
    }

    /** The current card is passed for this session (확정·보류 or 건너뛰기, nothing saved). Returns at once. */
    fun pass() {
        intent {
            if (phase != HomeFlowPhase.ACTIVE) return@intent
            val card = queue.firstOrNull() ?: return@intent
            passedIds += card.item.id
            queue = queue.drop(1)
            remaining = (remaining - 1).coerceAtLeast(queue.size)
            settle()
        }
    }

    /** 처음부터 다시 보기: forgets this session's passes and reads the first page. Returns at once. */
    fun restart() {
        intent {
            val g = group ?: return@intent
            reset()
            group = g
            initialLoad(null)
        }
    }

    /** App foreground: rebuilds the queue around the current card. Nothing on the summary. Returns at once. */
    fun foreground() {
        intent {
            val g = group ?: return@intent
            val keep = queue.firstOrNull()?.takeIf { phase == HomeFlowPhase.ACTIVE } ?: return@intent
            // A card without an anchor cannot anchor the window: the next one with an anchor, else the first page.
            // Anchor-less cards between the current card and that anchor stay only if they are in the window.
            val anchor = queue.firstNotNullOfOrNull { it.anchorCursor }
            loadJob?.cancel()
            appendJob?.cancel(); appendJob = null
            refreshing = true
            publish()
            loadJob = request({ read(g, anchor) }) { result ->
                loadJob = null
                refreshing = false
                if (result is ClientResult.Success) rebuild(result.value, keep.item)
                settle()
            }
        }
    }

    /** Repeats the read that left the flow in [HomeFlowPhase.ERROR]. Returns at once. */
    fun retry() {
        intent {
            if (phase != HomeFlowPhase.ERROR) return@intent
            val again = retryLast ?: return@intent
            // Back to LOADING first: settle derives the phase again only outside ERROR.
            phase = HomeFlowPhase.LOADING
            error = null
            retryLast = null
            publish()
            again()
        }
    }

    override fun onSessionChanged(current: SessionSnapshot) {
        reset()
        publish()
    }

    private fun reset() {
        loadJob?.cancel(); loadJob = null
        appendJob?.cancel(); appendJob = null
        group = null
        passedIds.clear(); skippedBefore.clear()
        queue = emptyList(); next = null; remaining = 0
        phase = HomeFlowPhase.LOADING; error = null; refreshing = false
        retryLast = null
    }

    private fun initialLoad(anchor: String?) {
        val g = group ?: return
        phase = HomeFlowPhase.LOADING
        error = null
        publish()
        loadJob = request({ read(g, anchor) }) { result ->
            loadJob = null
            when (result) {
                is ClientResult.Success -> rebuild(result.value, keep = null)
                is ClientResult.Failure -> fail(result.error) { initialLoad(anchor) }
            }
            settle()
        }
    }

    /** Reads the page after the queue; with the queue already empty a failure is an ERROR. */
    private fun append() {
        val g = group ?: return
        val cursor = next ?: return
        appendJob = request({ home.group(g, cursor) }) { result ->
            appendJob = null
            when (result) {
                is ClientResult.Success -> {
                    val page = result.value
                    val known = queue.mapTo(hashSetOf()) { it.item.id }
                    queue = queue + page.items.filter { it.id !in known && seen(it.id).not() }.map { card(it, page) }
                    next = page.nextCursor
                    // Its totalCount also counts cards saved in front since the last window: keep the total,
                    // and on the last page the queue is exactly what is left.
                    remaining = if (next == null) queue.size else remaining.coerceAtLeast(queue.size)
                }
                is ClientResult.Failure -> if (queue.isEmpty()) fail(result.error) { append() }
            }
            // A failed read waits for the next signal (a pass or foreground) instead of looping.
            settle(readAhead = result is ClientResult.Success)
        }
    }

    /**
     * Replaces the queue from a first page or window. [keep]: the card shown when a foreground read
     * started. The cards in front of the start card are behind this session (an anchored start, or
     * cards saved elsewhere since): they join [skippedBefore] so a later page still counts them in front.
     */
    private fun rebuild(read: FlowRead, keep: WishlistItem?) {
        val page = read.page
        val ids = page.items.map { it.id }
        val from = startIndex(page, ids, keep)
        val before = read.frontIds.orEmpty() + ids.take(from)
        skippedBefore += before.filterNot { it in passedIds }
        queue = page.items.drop(from).filterNot { seen(it.id) }.map { card(it, page) }
        next = page.nextCursor
        val inWindow = queue.firstOrNull()?.let { ids.indexOf(it.item.id) } ?: ids.size
        val front = when {
            page.previousCursor == null -> inWindow
            read.frontIds != null -> read.frontIds.size + inWindow
            // The read-back failed or was cut off: this session's ids stand in for the cards in front.
            else -> passedIds.size + skippedBefore.size
        }
        remaining = (page.totalCount - front).coerceAtLeast(queue.size)
        phase = HomeFlowPhase.ACTIVE
        error = null
        retryLast = null
    }

    /**
     * The window around [anchor] (or the first page), then the pages in front of it while it has a
     * previous cursor. A failed or overlong read-back leaves [FlowRead.frontIds] null; only the window's
     * own failure fails the read.
     */
    private suspend fun read(g: HomeActionGroup, anchor: String?): ClientResult<FlowRead> {
        val page = when (val r = if (anchor != null) home.groupAround(g, anchor) else home.group(g)) {
            is ClientResult.Failure -> return r
            is ClientResult.Success -> r.value
        }
        var cursor = page.previousCursor ?: return ClientResult.Success(FlowRead(page, emptyList()))
        val front = ArrayDeque<String>()
        repeat(FRONT_PAGES_MAX) {
            val back = home.group(g, cursor, FRONT_PAGE) as? ClientResult.Success ?: return ClientResult.Success(FlowRead(page, null))
            // Newest first, each page newer than the one read before it.
            back.value.items.asReversed().forEach { front.addFirst(it.id) }
            cursor = back.value.previousCursor ?: return ClientResult.Success(FlowRead(page, front.toList()))
        }
        return ClientResult.Success(FlowRead(page, null))
    }

    private fun startIndex(page: ItemPage, ids: List<String>, keep: WishlistItem?): Int {
        keep?.let { k -> ids.indexOf(k.id).takeIf { it >= 0 }?.let { return it } }
        val anchor = page.anchor ?: return 0
        val resolved = anchor.resolvedItemId?.let(ids::indexOf)?.takeIf { it >= 0 } ?: return ids.size
        // The kept card left the group and the fallback is newer: everything from there on is behind us.
        return if (keep != null && !anchor.resolved && isNewer(page.items[resolved], keep)) resolved + 1 else resolved
    }

    /** The group order: newest first, then id. */
    private fun isNewer(a: WishlistItem, b: WishlistItem) =
        a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id > b.id)

    private fun seen(id: String) = id in passedIds || id in skippedBefore

    private fun card(item: WishlistItem, page: ItemPage) = HomeFlowCard(item, page.anchorCursors[item.id])

    private fun fail(cause: ClientError, again: () -> Unit) {
        phase = HomeFlowPhase.ERROR
        error = cause
        retryLast = again
    }

    /** Reads ahead near the end of the queue and derives the phase, then publishes. */
    private fun settle(readAhead: Boolean = true) {
        if (phase != HomeFlowPhase.ERROR) {
            // A running window read replaces the queue anyway; the page after it is read once it lands.
            if (readAhead && loadJob == null && queue.size <= STACK && next != null && appendJob == null) append()
            phase = when {
                queue.isNotEmpty() -> HomeFlowPhase.ACTIVE
                loadJob != null || appendJob != null -> HomeFlowPhase.LOADING
                else -> HomeFlowPhase.DONE.also { remaining = 0 }
            }
        }
        publish()
    }

    private fun publish() {
        val current = queue.firstOrNull()?.takeIf { phase == HomeFlowPhase.ACTIVE }
        val passed = passedIds.size
        mutable.value = HomeFlowState(
            group = group,
            phase = phase,
            current = current,
            upcoming = if (current != null) queue.drop(1).take(STACK) else emptyList(),
            position = if (current != null) passed + 1 else passed,
            total = passed + remaining,
            passed = passed,
            error = error,
            refreshing = refreshing,
        )
    }
}
