package app.http

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Facet keys are omitted (not null) unless the request asked for them. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CandidatePageDto(
    val items: List<WishlistItemDto>,
    val totalCount: Long,
    val nextCursor: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val categoryFacets: List<CategoryFacetDto>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val unassignedCategoryCount: Long? = null,
)

@Serializable
data class CategoryFacetDto(val categoryId: String, val parentId: String, val count: Long)

@Serializable
data class CandidateMoveDto(val items: List<MovedItemDto>, val purposes: List<MovedPurposeDto>)

@Serializable
data class MovedItemDto(val id: String, val version: Int, val purposeId: String)

@Serializable
data class MovedPurposeDto(val id: String, val candidateCount: Long, val membershipVersion: Int)
