package app.wishlist.android.feature.category

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * C5 D10 page-level photo layout (pure, JVM-tested). A page of cards stays a skeleton until every
 * card's photo size is known (or failed / has no photo) or [timeoutMs] passed since the page came;
 * then the whole page is laid out at once and its ratios never change again:
 * - known size → its own height/width ratio (no clamp, D10),
 * - no photo, failed, or still unknown at the timeout → 1:1 (a late photo then fills that square).
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

    /** The laid-out height/width ratio of [id], or null while its page is still a skeleton. */
    fun ratioOf(id: String): Float? = frozen[id]

    /**
     * Lays out [page] if it is ready at [elapsedMs] since it arrived, using [photos]. Returns true when
     * the page is laid out (now or before). Cards already laid out keep their ratio.
     */
    fun settle(page: List<Card>, photos: Map<String, Photo>, elapsedMs: Long): Boolean {
        if (page.all { it.id in frozen }) return true
        val waiting = page.any { it.hasPhoto && it.id !in frozen && photos[it.id] == null }
        if (waiting && elapsedMs < timeoutMs) return false
        page.forEach { card ->
            if (card.id in frozen) return@forEach
            val photo = photos[card.id]
            frozen[card.id] = if (card.hasPhoto && photo is Photo.Sized && photo.width > 0 && photo.height > 0) {
                photo.height.toFloat() / photo.width
            } else 1f
        }
        return true
    }

    /** Forgets every page (a refresh starts the list again). */
    fun reset() = frozen.clear()

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

/** Limits one list's photo requests to [PhotoPagePlanner.MAX_CONCURRENT]; a cancelled waiter never starts. */
class PhotoRequestGate(permits: Int = PhotoPagePlanner.MAX_CONCURRENT) {
    private val semaphore = Semaphore(permits)
    val available: Int get() = semaphore.availablePermits
    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }
}
