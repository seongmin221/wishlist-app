package app.wishlist.shared.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire shape of the server item (B1 + B2 category snapshot). Every enum is a raw string so an
 * unknown server value never fails deserialization; [mapItem] interprets it field by field.
 * Price stays a [JsonElement] to keep the exact JSON number literal (see [readPrice]).
 * A missing required field or a wrong JSON type fails decoding and becomes INVALID_RESPONSE.
 * Kotlin-internal: neither these types nor Ktor reach the ObjC framework.
 */
@Serializable
internal data class WishlistItemDto(
    val id: String,
    val clientSubmissionId: String,
    /** Kept raw: kotlinx would accept a quoted "2" as an Int, but the contract is a JSON number. */
    val version: JsonElement,
    val sourceUrl: String,
    val product: ProductDto,
    val category: CategoryDto,
    val purpose: PurposeDto,
    val analysis: AnalysisDto,
    val reviewStatus: String,
    val lifecycleStatus: String,
    val requiredAction: String,
    val createdAt: String,
    val updatedAt: String,
    val manualCompletionAt: String? = null,
    val allowedActions: List<String> = emptyList(),
    val clientCreatedAt: String? = null,
)

@Serializable
internal data class ProductDto(
    val name: String? = null,
    val imageUrl: String? = null,
    val price: JsonElement? = null,
    val currency: String? = null,
    val brand: String? = null,
    val merchant: String? = null,
    val metadataCheckedAt: String? = null,
    val nameSource: String? = null,
    val imageSource: String? = null,
)

@Serializable
internal data class CategoryDto(
    val id: String? = null,
    val source: String? = null,
    val missingReason: String? = null,
    val name: String? = null,
    val parentId: String? = null,
    val kind: String? = null,
)

@Serializable
internal data class PurposeDto(val id: String? = null, val source: String? = null)

@Serializable
internal data class AnalysisDto(val status: String, val failureCode: String? = null)
