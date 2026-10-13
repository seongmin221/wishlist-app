package app.http

import app.media.MediaUrls
import app.wishlist.*
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ReadCardDto(val item: WishlistItemDto, val anchorCursor: String)
@Serializable
data class ReadWindowDto(
    val items: List<ReadCardDto>, val totalCount: Long, val previousCursor: String?, val nextCursor: String?,
    val requestedAnchorItemId: String?, val resolvedAnchorItemId: String?, val anchorResolved: Boolean?,
)

object ReadWindowViewMapper {
    fun card(context: WishlistReadCursorCodec.OwnerContext, endpoint: ReadEndpoint, scope: ReadScope, item: WishlistItem, urls: MediaUrls) = ReadCardDto(
        WishlistItemViewMapper.map(item, urls),context.encode(endpoint,scope,ReadCursorUse.ANCHOR,ReadPosition(item.createdAt,item.id)))
    fun map(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, page: ReadPage, urls: MediaUrls): ReadWindowDto {
        val context=WishlistReadCursorCodec.forOwner(owner)
        return ReadWindowDto(page.items.map { card(context,endpoint,scope,it,urls) },page.totalCount,
            page.previous?.let { context.encode(endpoint,scope,if(page.previousInclusive) ReadCursorUse.PREVIOUS_INCLUSIVE else ReadCursorUse.PREVIOUS,it) },
            page.next?.let { context.encode(endpoint,scope,if(page.nextInclusive) ReadCursorUse.NEXT_INCLUSIVE else ReadCursorUse.NEXT,it) },
            page.requestedAnchorItemId?.toString(),page.resolvedAnchorItemId?.toString(),page.anchorResolved)
    }
}
