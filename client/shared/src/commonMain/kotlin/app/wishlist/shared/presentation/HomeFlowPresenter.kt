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
 *   current card: counted in the window when it has no previous cursor, else this session's passed
 *   and skipped ids. Other devices' changes are corrected on the next read.
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
            val anchor = queue.firstNotNullOfOrNull { it.anchorCursor }
            loadJob?.cancel()
            appendJob?.cancel(); appendJob = null
            refreshing = true
            publish()
            loadJob = request({ if (anchor != null) home.groupAround(g, anchor) else home.group(g) }) { result ->
                loadJob = null
                refreshing = false
                if (result is ClientResult.Success) rebuild(result.value, keep.item, skipFront = false)
                settle()
            }
        }
    }

    /** Repeats the read that left the flow in [HomeFlowPhase.ERROR]. Returns at once. */
    fun retry() {
        intent { if (phase == HomeFlowPhase.ERROR) retryLast?.invoke() }
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
        loadJob = request({ if (anchor != null) home.groupAround(g, anchor) else home.group(g) }) { result ->
            loadJob = null
            when (result) {
                is ClientResult.Success -> rebuild(result.value, keep = null, skipFront = anchor != null)
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
                    // A next page always has items in front: this session's passed and skipped ids stand in for them.
                    remaining = (page.totalCount - passedIds.size - skippedBefore.size).coerceAtLeast(queue.size)
                }
                is ClientResult.Failure -> if (queue.isEmpty()) fail(result.error) { append() }
            }
            settle()
        }
    }

    /**
     * Replaces the queue from a first page or window. [keep]: the card shown when a foreground read
     * started. [skipFront]: the cards in front of the start card are behind this session (anchored start).
     */
    private fun rebuild(page: ItemPage, keep: WishlistItem?, skipFront: Boolean) {
        val ids = page.items.map { it.id }
        val from = startIndex(page, ids, keep)
        if (skipFront) skippedBefore += ids.take(from)
        queue = page.items.drop(from).filterNot { seen(it.id) }.map { card(it, page) }
        next = page.nextCursor
        val front = if (page.previousCursor == null) {
            queue.firstOrNull()?.let { ids.indexOf(it.item.id) } ?: ids.size
        } else {
            passedIds.size + skippedBefore.size
        }
        remaining = (page.totalCount - front).coerceAtLeast(queue.size)
        phase = HomeFlowPhase.ACTIVE
        error = null
        retryLast = null
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
    private fun settle() {
        if (phase != HomeFlowPhase.ERROR) {
            // A running window read replaces the queue anyway; the page after it is read once it lands.
            if (loadJob == null && queue.size <= STACK && next != null && appendJob == null) append()
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
