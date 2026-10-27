package app.wishlist.shared.data.fake

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.ItemAnchor
import kotlin.time.Instant

/** One page or anchor window of a Fake scope. [anchorOf] makes a card's anchor token in the same scope. */
internal class FakeSlice<T>(
    val items: List<T>,
    val previousCursor: String?,
    val nextCursor: String?,
    val anchor: ItemAnchor?,
    val anchorOf: (T) -> String,
)

/*
 * Token = "$prefix$use:$createdAt|$id". The prefix carries owner + endpoint + scope, so another
 * scope's token is refused like the server's; [use] is the server's purpose of the token:
 * a = card anchor, n/p = exclusive next (older) / previous (newer), ni/pi = inclusive return cursor.
 * The full instant (not millis) keeps items sharing a millisecond from falling between pages.
 */
private const val ANCHOR = "a"
private const val NEXT = "n"
private const val PREVIOUS = "p"
private const val NEXT_INCLUSIVE = "ni"
private const val PREVIOUS_INCLUSIVE = "pi"

private typealias Key = Pair<Instant, String>

/** `createdAt DESC, id DESC` order: a positive result means [a] is newer than [b]. */
private fun compareKeys(a: Key, b: Key): Int = compareValuesBy(a, b, { it.first }, { it.second })

/**
 * ITEM-02/HOME-02 window over [sorted] (newest first, keyed by [key]) with the server's rules
 * (docs/architecture/server/wishlist-item-read-api.md "공용 카드와 page/window"). Page mode
 * ([anchor] null) reads [limit] from [cursor]; anchor mode returns up to [before] + 1 + [after]
 * around the anchor or its fallback (next older, else next newer). A cursor exists only when items
 * lie beyond the window; a page whose direction emptied returns an inclusive cursor to the nearest
 * item left on the other side. [queryCode]/[cursorCode] are the endpoint's 400 codes.
 */
internal fun <T> fakeWindow(
    sorted: List<T>, key: (T) -> Pair<Instant, String>, prefix: String, cursor: String?, limit: Int?,
    anchor: String?, before: Int, after: Int,
    queryCode: String = "INVALID_WISHLIST_ITEM_QUERY", cursorCode: String = "INVALID_WISHLIST_ITEM_CURSOR",
): ClientResult<FakeSlice<T>> {
    val badQuery = ClientResult.Failure(ClientError(ErrorKind.VALIDATION, queryCode))
    val badCursor = ClientResult.Failure(ClientError(ErrorKind.VALIDATION, cursorCode))
    fun token(use: String, k: Key) = "$prefix$use:${k.first}|${k.second}"
    fun decode(raw: String): Pair<String, Key>? {
        if (!raw.startsWith(prefix)) return null
        val body = raw.removePrefix(prefix)
        val use = body.substringBefore(':', "")
        val rest = body.substringAfter(':', "")
        val at = rest.substringBeforeLast('|', "").let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val id = rest.substringAfterLast('|', "").takeIf { it.isNotEmpty() } ?: return null
        return use to (at to id)
    }
    val anchorOf: (T) -> String = { token(ANCHOR, key(it)) }
    val keys = sorted.map(key)

    /** Cursors of the contiguous slice [from, to): only for items outside it. */
    fun slice(from: Int, to: Int, resolvedAnchor: ItemAnchor?) = ClientResult.Success(FakeSlice(
        items = sorted.subList(from, to),
        previousCursor = if (from > 0 && to > from) token(PREVIOUS, keys[from]) else null,
        nextCursor = if (to < sorted.size && to > from) token(NEXT, keys[to - 1]) else null,
        anchor = resolvedAnchor, anchorOf = anchorOf,
    ))

    if (anchor != null) {
        if (cursor != null || limit != null || before !in 0..MAX_SPAN || after !in 0..MAX_SPAN) return badQuery
        val (use, position) = decode(anchor) ?: return badCursor
        if (use != ANCHOR) return badCursor
        val exact = keys.indexOfFirst { compareKeys(it, position) == 0 }
        val older = keys.indexOfFirst { compareKeys(it, position) < 0 }
        val center = when {
            exact >= 0 -> exact
            older >= 0 -> older
            else -> keys.indexOfLast { compareKeys(it, position) > 0 } // -1 when the scope is empty
        }
        if (center < 0) return ClientResult.Success(FakeSlice(emptyList(), null, null, ItemAnchor(position.second, null, false), anchorOf))
        val resolved = ItemAnchor(position.second, keys[center].second, exact >= 0)
        return slice(maxOf(0, center - before), minOf(sorted.size, center + 1 + after), resolved)
    }

    if (limit == null || limit !in 1..MAX_LIMIT) return badQuery
    if (cursor == null) return slice(0, minOf(sorted.size, limit), null)
    val (use, boundary) = decode(cursor) ?: return badCursor
    return when (use) {
        NEXT, NEXT_INCLUSIVE -> {
            val from = keys.indexOfFirst { c -> compareKeys(c, boundary).let { if (use == NEXT) it < 0 else it <= 0 } }
            if (from >= 0) return slice(from, minOf(sorted.size, from + limit), null)
            // Nothing older is left: point back (inclusive) at the nearest remaining newer item.
            val back = keys.indexOfLast { compareKeys(it, boundary) >= 0 }
            ClientResult.Success(FakeSlice(emptyList(), back.takeIf { it >= 0 }?.let { token(PREVIOUS_INCLUSIVE, keys[it]) }, null, null, anchorOf))
        }
        PREVIOUS, PREVIOUS_INCLUSIVE -> {
            // Newer items form a prefix; the page is its last [limit] (the ones closest to the boundary).
            val to = keys.indexOfLast { c -> compareKeys(c, boundary).let { if (use == PREVIOUS) it > 0 else it >= 0 } } + 1
            if (to > 0) return slice(maxOf(0, to - limit), to, null)
            val back = keys.indexOfFirst { compareKeys(it, boundary) <= 0 }
            ClientResult.Success(FakeSlice(emptyList(), null, back.takeIf { it >= 0 }?.let { token(NEXT_INCLUSIVE, keys[it]) }, null, anchorOf))
        }
        else -> badCursor
    }
}

/**
 * ITEM-02/HOME-02 bounds, checked before any scope lookup (a bad limit is 400 even for a missing
 * scope), like the server: page [limit] 1~100, anchor [before]/[after] 0~20.
 */
internal fun validWindowQuery(limit: Int?, anchor: String?, before: Int, after: Int): Boolean =
    if (anchor == null) limit != null && limit in 1..MAX_LIMIT else before in 0..MAX_SPAN && after in 0..MAX_SPAN

private const val MAX_SPAN = 20
private const val MAX_LIMIT = 100
