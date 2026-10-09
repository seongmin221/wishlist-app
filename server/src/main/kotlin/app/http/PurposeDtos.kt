package app.http

import app.purpose.*
import kotlinx.serialization.Serializable

@Serializable data class PurposeActivityDto(val at: String, val kind: String)
@Serializable data class PurposeDetailDto(
    val id: String, val name: String, val description: String?, val colorKey: String, val iconKey: String,
    val candidateCount: Long, val membershipVersion: Int, val version: Int, val activity: PurposeActivityDto,
    val createdAt: String, val updatedAt: String, val allowedActions: List<String>,
)
@Serializable data class PurposeCreationDto(val purpose: PurposeDetailDto, val activeCount: Int, val purposeLimit: Int = PurposeLimits.ACTIVE_LIMIT)
@Serializable data class PurposeArchiveSummaryDto(val count: Int, val recentTitles: List<String>)
@Serializable data class PurposeSelectItemDto(val id: String, val name: String, val colorKey: String, val iconKey: String, val version: Int)
@Serializable data class PurposePreviewDto(val itemId: String, val imageUrl: String?)
@Serializable data class PurposeSummaryItemDto(
    val id: String, val name: String, val colorKey: String, val iconKey: String, val version: Int,
    val description: String?, val candidateCount: Long, val activity: PurposeActivityDto, val previews: List<PurposePreviewDto>,
)
@Serializable data class PurposeSelectListDto(val projection: String, val purposes: List<PurposeSelectItemDto>, val nextCursor: String?,
    val activeCount: Int, val purposeLimit: Int, val archiveSummary: PurposeArchiveSummaryDto)
@Serializable data class PurposeSummaryListDto(val projection: String, val purposes: List<PurposeSummaryItemDto>, val nextCursor: String?,
    val activeCount: Int, val purposeLimit: Int, val archiveSummary: PurposeArchiveSummaryDto)

internal fun Purpose.activityDto() = PurposeActivityDto(activityAt.toString(), activityKind.name)
internal fun Purpose.toDto() = PurposeDetailDto(id.toString(), input.name, input.description, input.color.name, input.icon.name,
    candidateCount, membershipVersion, version, activityDto(), createdAt.toString(), updatedAt.toString(), allowedActions.map { it.name })
/** B3 has no archive records; count is the real ARCHIVED purpose count and titles come from B10 records. */
internal fun PurposePage.archiveSummary() = PurposeArchiveSummaryDto(archivedCount, emptyList())
