package app.http

import app.wishlist.AnalysisStatus
import app.wishlist.CategoryMissingReason
import app.wishlist.ItemAction
import app.wishlist.LifecycleStatus
import app.wishlist.RequiredAction
import app.wishlist.ReviewStatus
import app.wishlist.ValueSource
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class WishlistItemDto(
    val id: String,
    val clientSubmissionId: String,
    val version: Int,
    val sourceUrl: String,
    val product: ProductDto,
    val category: CategoryDto,
    val purpose: PurposeDto,
    val analysis: AnalysisDto,
    val reviewStatus: ReviewStatus,
    val lifecycleStatus: LifecycleStatus,
    val requiredAction: RequiredAction,
    val createdAt: String,
    val updatedAt: String,
    val manualCompletionAt: String? = null,
    val allowedActions: Set<ItemAction> = emptySet(),
    val clientCreatedAt: String? = null,
)

@Serializable
data class ProductDto(
    val name: String? = null,
    val imageUrl: String? = null,
    @Serializable(with = DecimalJsonSerializer::class)
    val price: BigDecimal? = null,
    val currency: String? = null,
    val brand: String? = null,
    val merchant: String? = null,
    val metadataCheckedAt: String? = null,
    val nameSource: ValueSource? = null,
    val imageSource: ValueSource? = null,
)

@Serializable
data class CategoryDto(
    val id: String? = null,
    val source: ValueSource? = null,
    val missingReason: CategoryMissingReason? = null,
)

@Serializable
data class PurposeDto(
    val id: String? = null,
    val source: ValueSource = ValueSource.UNASSIGNED,
)

@Serializable
data class AnalysisDto(val status: AnalysisStatus, val failureCode: String? = null)
