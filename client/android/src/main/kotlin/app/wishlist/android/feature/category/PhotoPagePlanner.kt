package app.wishlist.android.feature.category

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * C5 D10 page-level photo layout (pure, JVM-tested). A page of cards stays a skeleton until every
 * card's photo size is known (or failed / has no photo) or [timeoutMs] passed since the page came;
 * then the whole page is laid out at once and its ratios never change again:
 * - known size → its own height/width ratio (no clamp, D10),
 * - no photo, failed, or still unknown at the timeout → 1:1 (a late photo then fills that square).
 * Pages are laid out in list order: a later page waits while an earlier one is a skeleton (it may have
 * all its sizes first), so the masonry never places a later card before an earlier one and laid-out
 * cards never move (like iOS `PhotoPagePlan.layOut`, which stops at the first unfinished page).
 */
class PhotoPagePlanner(private val timeoutMs: Long = PAGE_TIMEOUT_MS) {
    /** A card of a page: [hasPhoto] false = no usable image URL (drawn as the 1:1 placeholder). */
    data class Card(val id: String, val hasPhoto: Boolean)

    /** What is known about a card's photo. */
    sealed interface Photo {
        data class Sized(val width: Int, val height: Int) : Photo
        data object Failed : Photo
    }

    private val frozen = mutableMapOf<String, Float>()
    private var laidPages = 0

    /** How many pages (from the first) are laid out. */
    val laidOutPages: Int get() = laidPages

    /** The laid-out height/width ratio of [id], or null while its page is still a skeleton. */
    fun ratioOf(id: String): Float? = frozen[id]

    /**
     * Lays out page [index] (0 = the first page) if every earlier page is laid out and it is ready at
     * [elapsedMs] since it arrived, using [photos]. Returns true when the page is laid out (now or before).
     */
    fun settle(index: Int, page: List<Card>, photos: Map<String, Photo>, elapsedMs: Long): Boolean {
        if (index < laidPages) return true
        if (index > laidPages) return false // an earlier page is still a skeleton
        val waiting = page.any { it.hasPhoto && it.id !in frozen && photos[it.id] == null }
        if (waiting && elapsedMs < timeoutMs) return false
        page.forEach { card ->
            if (card.id in frozen) return@forEach
            val photo = photos[card.id]
            frozen[card.id] = if (card.hasPhoto && photo is Photo.Sized && photo.width > 0 && photo.height > 0) {
                photo.height.toFloat() / photo.width
            } else 1f
        }
        laidPages++
        return true
    }

    /** Forgets every page (a refresh starts the list again). */
    fun reset() {
        frozen.clear()
        laidPages = 0
    }

    companion object {
        const val PAGE_TIMEOUT_MS = 3_000L

        /** At most this many photo requests run at once in one list. */
        const val MAX_CONCURRENT = 6

        /**
         * The box a card photo is decoded to fit (keeping its ratio): column width × 3 column widths. A
         * fitted photo has at most colWidth × colWidth × 3 pixels; only very tall photos lose resolution.
         */
        fun decodeBox(columnWidthPx: Int): Pair<Int, Int> {
            val w = columnWidthPx.coerceAtLeast(1)
            return w to w * 3
        }

        /** Pixel size of a [width]×[height] photo fitted into [decodeBox] (never upscaled), ratio kept. */
        fun fittedSize(width: Int, height: Int, columnWidthPx: Int): Pair<Int, Int> {
            val (bw, bh) = decodeBox(columnWidthPx)
            val scale = minOf(1.0, bw.toDouble() / width, bh.toDouble() / height)
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }
    }
}

/**
 * One layout of a category list: its pages, planner and laid-out ratios. A restart (refresh, another
 * category, a laid-out card's photo changed) makes a new one, so a page job still running for an older
 * layout settles and publishes only into that one and never into the layout on screen.
 */
internal class PhotoGridLayout(timeoutMs: Long = PhotoPagePlanner.PAGE_TIMEOUT_MS) {
    private val planner = PhotoPagePlanner(timeoutMs)
    /** Item ids per page (one ITEM-02 answer each), in list order. */
    val pages = mutableStateListOf<List<String>>()
    /** The photo URL each paged card was laid out with. */
    val photos = mutableMapOf<String, String?>()
    /** Laid-out ratios as state, so a settled page recomposes the grid (the planner itself is plain). */
    val ratios = mutableStateMapOf<String, Float>()

    /** Polls page [index] every 100 ms until the planner lays it out, then publishes its ratios. */
    suspend fun settlePage(index: Int, cards: List<PhotoPagePlanner.Card>, photosNow: () -> Map<String, PhotoPagePlanner.Photo>, elapsedMs: () -> Long) {
        // In list order: a later page waits for the earlier ones even when its photos came first.
        while (!planner.settle(index, cards, photosNow(), elapsedMs())) delay(100)
        cards.forEach { card -> planner.ratioOf(card.id)?.let { ratios[card.id] = it } }
    }
}

/**
 * One list's photo sizes, each tied to the URL it was read from (pure, JVM-tested). Only the latest
 * [begin] of a card may [finish], so a late answer for a replaced photo (or a cancelled read) is dropped;
 * [photosFor] gives a layout only sizes of the photos its cards show now.
 */
class PhotoMeasurements {
    private var serial = 0
    private val latest = mutableMapOf<String, Pair<String?, Int>>()
    private val measured = mutableMapOf<String, Pair<String?, PhotoPagePlanner.Photo>>()

    /** A read of [id]'s photo at [url]: its token for [finish], or null when that size is already known. */
    fun begin(id: String, url: String?): Int? {
        if (id in measured && measured[id]?.first == url) return null
        latest[id] = url to ++serial
        return serial
    }

    /** Records [photo] for [id] if [token] is its latest [begin] (an older read's answer is dropped). */
    fun finish(id: String, token: Int, photo: PhotoPagePlanner.Photo) {
        val wanted = latest[id] ?: return
        if (wanted.second == token) measured[id] = wanted.first to photo
    }

    /** The sizes of [cards]' current photos (id to photo URL); a size read from another URL is left out. */
    fun photosFor(cards: Map<String, String?>): Map<String, PhotoPagePlanner.Photo> =
        cards.mapNotNull { (id, url) -> measured[id]?.takeIf { it.first == url }?.let { id to it.second } }.toMap()
}

/** Limits one list's photo requests to [PhotoPagePlanner.MAX_CONCURRENT]; a cancelled waiter never starts. */
class PhotoRequestGate(permits: Int = PhotoPagePlanner.MAX_CONCURRENT) {
    private val semaphore = Semaphore(permits)
    val available: Int get() = semaphore.availablePermits
    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }
}
