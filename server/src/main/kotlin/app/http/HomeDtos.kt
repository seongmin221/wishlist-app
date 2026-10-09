package app.http

import app.home.HomeReadSummary
import app.wishlist.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class HomeActionGroupDto(val group: HomeActionGroup, val count: Long, val previews: List<ReadCardDto>)
@Serializable data class HomeDto(val actionGroups: List<HomeActionGroupDto>, val recentPurposes: List<PurposeSummaryItemDto>)

object HomeViewMapper {
    fun map(owner: UUID, summary: HomeReadSummary): HomeDto {
        val context=WishlistReadCursorCodec.forOwner(owner)
        return HomeDto(
        summary.actionGroups.map { group ->
            HomeActionGroupDto(group.group, group.count, group.items.map {
                ReadWindowViewMapper.card(context, ReadEndpoint.HOME_ACTION_ITEMS, ReadScope.Action(group.group), it)
            })
        },
        summary.recentPurposes.map { it.summaryDto() },
    )
    }
}
