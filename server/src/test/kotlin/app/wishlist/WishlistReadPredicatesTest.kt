package app.wishlist

import kotlin.test.*

class WishlistReadPredicatesTest {
    @Test fun whitespace_matches_every_jvm_char() {
        for (n in 0..0xffff) assertEquals(n.toChar().isWhitespace(), n.toChar() in WishlistReadPredicates.POLICY_WHITESPACE, "U+${n.toString(16)}")
        assertEquals(28, WishlistReadPredicates.POLICY_WHITESPACE.length)
        assertFalse('\u0085' in WishlistReadPredicates.POLICY_WHITESPACE)
        assertFalse('\u200b' in WishlistReadPredicates.POLICY_WHITESPACE)
        for (c in listOf('\u00a0','\u2007','\u202f','\u2028','\u2029')) assertTrue(c in WishlistReadPredicates.POLICY_WHITESPACE)
    }
}
