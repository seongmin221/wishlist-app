package app.http

import app.wishlist.*
import io.ktor.http.Parameters
import java.security.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Counts the actual JCA digest operation, rather than checking mapper implementation details. */
class ReadCursorHashReuseTest {
    class CountingDigest : MessageDigestSpi() {
        private val delegate = MessageDigest.getInstance("SHA-256", "SUN")
        override fun engineUpdate(input: Byte) = delegate.update(input)
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = delegate.update(input,offset,len)
        override fun engineDigest(): ByteArray { digests++; return delegate.digest() }
        override fun engineReset() = delegate.reset()
        companion object { var digests = 0 }
    }
    private fun counting(block: () -> Unit) {
        val provider = object : Provider("ReadCursorTest", "1", "Test digest counter") { init { put("MessageDigest.SHA-256",CountingDigest::class.java.name) } }
        Security.insertProviderAt(provider,1)
        try { CountingDigest.digests=0;block() } finally { Security.removeProvider(provider.name) }
    }
    @Test fun one_response_hashes_owner_once_for_all_cards_and_edges() {
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val items=(1..100).map {
            val stored=StoredWishlistItemState(UUID.randomUUID(),owner,1,1,
                WishlistItemState(AnalysisStatus.READY,ReviewStatus.CONFIRMED,LifecycleStatus.ACTIVE,"name","C026",null,null),
                ValueSource.USER,null,ValueSource.UNASSIGNED,ValueSource.USER,null,emptySet())
            WishlistItem(stored,UUID.randomUUID(),"https://example.com/item",null,null,null,time,time)
        }
        counting {
            val page=ReadPage(items,102,ReadPosition(time,items.first().id),ReadPosition(time,items.last().id),null,null,null)
            val dto=ReadWindowViewMapper.map(owner,ReadEndpoint.WISHLIST_ITEMS,ReadScope.PurposeUnassigned,page)
            assertEquals(100,dto.items.size);assertNotNull(dto.previousCursor);assertNotNull(dto.nextCursor)
            assertEquals(1,CountingDigest.digests)
        }
    }
    @Test fun previous_page_cursor_is_decoded_once() {
        val owner=UUID.randomUUID()
        val position=ReadPosition(Instant.parse("2026-10-07T10:00:00Z"),UUID.randomUUID())
        val token=WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS,ReadScope.PurposeUnassigned,ReadCursorUse.PREVIOUS,position)
        counting {
            assertIs<ReadQueryParseResult.Valid>(WishlistReadQueryParser.wishlist(owner,Parameters.build {
                append("purposeUnassigned","true");append("cursor",token)
            }))
            assertEquals(1,CountingDigest.digests)
        }
    }
}
