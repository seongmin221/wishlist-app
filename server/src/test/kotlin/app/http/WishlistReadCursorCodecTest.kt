package app.http

import app.category.CategoryRef
import app.wishlist.*
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class WishlistReadCursorCodecTest {
    private val owner = UUID.randomUUID()
    private val position = ReadPosition(Instant.parse("2026-10-07T10:00:00.123456Z"), UUID.randomUUID())
    private val scope = ReadScope.Category(CategoryRef.Public("C026"))
    @Test fun cursor_binds_owner_scope_endpoint_and_use() {
        val token = WishlistReadCursorCodec.encode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.ANCHOR, position)
        assertEquals(position, WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.ANCHOR, token))
        assertNull(WishlistReadCursorCodec.decode(UUID.randomUUID(), ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.ANCHOR, token))
        for (other in listOf(ReadScope.Category(CategoryRef.Public("C027")), ReadScope.Purpose(UUID.randomUUID()), ReadScope.PurposeUnassigned, ReadScope.Action(HomeActionGroup.INFORMATION_COMPLETION)))
            assertNull(WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, other, ReadCursorUse.ANCHOR, token))
        assertNull(WishlistReadCursorCodec.decode(owner, ReadEndpoint.HOME_ACTION_ITEMS, scope, ReadCursorUse.ANCHOR, token))
        for (use in listOf(ReadCursorUse.NEXT, ReadCursorUse.PREVIOUS)) assertNull(WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, use, token))
        for (s in listOf<ReadScope>(ReadScope.PurposeUnassigned, ReadScope.Purpose(UUID.randomUUID()), ReadScope.Category(CategoryRef.Custom(UUID.randomUUID())), ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW))) {
            val t = WishlistReadCursorCodec.encode(owner, ReadEndpoint.WISHLIST_ITEMS, s, ReadCursorUse.NEXT, position)
            assertEquals(position, WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, s, ReadCursorUse.NEXT, t))
        }
    }
    @Test fun malformed_cursor_is_rejected_without_throwing() {
        val token = WishlistReadCursorCodec.encode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, position)
        val fields = String(Base64.getUrlDecoder().decode(token)).split("|")
        fun raw(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val bad = mutableListOf("", "not base64!", "a".repeat(2049), raw("v1|short"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xc3.toByte(), 0x28)))
        for ((index, replacements) in mapOf(0 to listOf("v9"), 1 to listOf("OTHER"), 2 to listOf("foreign"), 3 to listOf("OTHER"),
            4 to listOf("C027"), 5 to listOf("UNKNOWN"), 6 to listOf("-1", "253402300800000000", "9223372036854775808", "x"), 7 to listOf("1-1-1-1-1", "not-uuid"))) {
            for (replacement in replacements) bad += raw(fields.mapIndexed { i, value -> if (i == index) replacement else value }.joinToString("|"))
        }
        for (value in bad) assertNull(WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, value), value)
        for (time in listOf(Instant.EPOCH, Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            val p = position.copy(createdAt = time)
            val t = WishlistReadCursorCodec.encode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, p)
            assertEquals(p, WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, t))
        }
        val ownChanged = raw(fields.mapIndexed { i, value -> if (i == 6) "1" else value }.joinToString("|"))
        assertNotNull(WishlistReadCursorCodec.decode(owner, ReadEndpoint.WISHLIST_ITEMS, scope, ReadCursorUse.NEXT, ownChanged))
    }
}
