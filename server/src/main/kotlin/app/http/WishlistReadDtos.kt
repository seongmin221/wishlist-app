package app.http

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
    fun card(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, item: WishlistItem) = ReadCardDto(
        WishlistItemViewMapper.map(item), WishlistReadCursorCodec.encode(owner,endpoint,scope,ReadCursorUse.ANCHOR,ReadPosition(item.createdAt,item.id)))
    fun map(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, page: ReadPage) = ReadWindowDto(
        page.items.map { card(owner,endpoint,scope,it) },page.totalCount,
        page.previous?.let { WishlistReadCursorCodec.encode(owner,endpoint,scope,ReadCursorUse.PREVIOUS,it) },
        page.next?.let { WishlistReadCursorCodec.encode(owner,endpoint,scope,ReadCursorUse.NEXT,it) },
        page.requestedAnchorItemId?.toString(),page.resolvedAnchorItemId?.toString(),page.anchorResolved)
}
