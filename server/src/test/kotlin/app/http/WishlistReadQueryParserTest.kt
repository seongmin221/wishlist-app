package app.http

import app.wishlist.*
import io.ktor.http.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class WishlistReadQueryParserTest {
    private val owner = UUID.randomUUID()
    private val scope = ReadScope.PurposeUnassigned
    private val pos = ReadPosition(Instant.parse("2026-10-07T10:00:00Z"), UUID.randomUUID())
    private fun parameters(vararg values: Pair<String,String>) = Parameters.build { values.forEach { append(it.first, it.second) } }
    private fun wishlist(vararg values: Pair<String,String>) = WishlistReadQueryParser.wishlist(owner, parameters(*values))
    @Test fun query_modes_and_limits_are_exact() {
        assertEquals(ReadWindow.Page(40), assertIs<ReadQueryParseResult.Valid>(wishlist("purposeUnassigned" to "true")).query.window)
        assertEquals(ReadWindow.Page(20), assertIs<ReadQueryParseResult.Valid>(WishlistReadQueryParser.action(owner, parameters("group" to "INFORMATION_COMPLETION"))).query.window)
        for (limit in listOf("1", "100")) assertIs<ReadQueryParseResult.Valid>(wishlist("purposeUnassigned" to "true", "limit" to limit))
        val invalid = listOf(emptyArray(), arrayOf("categoryId" to "C999"), arrayOf("categoryId" to "G003"),
            arrayOf("purposeUnassigned" to "false"), arrayOf("purposeId" to "1-1-1-1-1"),
            arrayOf("purposeUnassigned" to "true", "categoryId" to "C026"), arrayOf("purposeUnassigned" to "true", "parentId" to "G003"),
            arrayOf("purposeUnassigned" to "true", "purposeUnassigned" to "true"), arrayOf("purposeUnassigned" to "true", "before" to "20")) +
            listOf("0", "101", "2147483648", "", "no", " 20 ").map { arrayOf("purposeUnassigned" to "true", "limit" to it) }
        for (p in invalid) assertEquals(ReadQueryParseResult.InvalidQuery, wishlist(*p), p.contentToString())
        for (bad in listOf(parameters(), parameters("group" to "NONE"), parameters("group" to "INFORMATION_COMPLETION", "action" to "CATEGORY_ASSIGNMENT")))
            assertEquals(ReadQueryParseResult.InvalidQuery, WishlistReadQueryParser.action(owner, bad))
        val anchor = WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.ANCHOR, pos)
        assertEquals(ReadWindow.Anchor(pos), assertIs<ReadQueryParseResult.Valid>(wishlist("purposeUnassigned" to "true", "anchor" to anchor)).query.window)
        assertEquals(ReadWindow.Anchor(pos,0,0), assertIs<ReadQueryParseResult.Valid>(wishlist("purposeUnassigned" to "true", "anchor" to anchor, "before" to "0", "after" to "0")).query.window)
        for (p in listOf(arrayOf("limit" to "40"), arrayOf("cursor" to anchor), arrayOf("before" to "21"), arrayOf("after" to "-1"), arrayOf("anchorItemId" to pos.id.toString())))
            assertEquals(ReadQueryParseResult.InvalidQuery, wishlist("purposeUnassigned" to "true", "anchor" to anchor, *p))
    }
    @Test fun cursor_use_and_scope_are_checked_before_io() {
        for ((use, direction) in listOf(ReadCursorUse.NEXT to ReadDirection.OLDER, ReadCursorUse.PREVIOUS to ReadDirection.NEWER)) {
            val token = WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS, scope, use, pos)
            assertEquals(ReadWindow.Page(40,pos,direction), assertIs<ReadQueryParseResult.Valid>(wishlist("purposeUnassigned" to "true", "cursor" to token)).query.window)
            assertEquals(ReadQueryParseResult.InvalidCursor, wishlist("purposeUnassigned" to "true", "anchor" to token))
        }
        for (token in listOf("bad", WishlistReadCursorCodec.forOwner(UUID.randomUUID()).encode(ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, pos),
            WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.HOME_ACTION_ITEMS, ReadScope.Action(HomeActionGroup.INFORMATION_COMPLETION), ReadCursorUse.NEXT, pos),
            WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS, ReadScope.Purpose(UUID.randomUUID()), ReadCursorUse.NEXT, pos),
            WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.ANCHOR, pos)))
            assertEquals(ReadQueryParseResult.InvalidCursor, wishlist("purposeUnassigned" to "true", "cursor" to token))
    }
}
