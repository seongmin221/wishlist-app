package app.wishlist.android.testing

import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.ItemAnalysis
import app.wishlist.shared.model.ItemCategory
import app.wishlist.shared.model.ItemPurpose
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.model.ProductSnapshot
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.ReviewStatus
import app.wishlist.shared.model.WishlistItem
import kotlin.time.Instant

/** Minimal server items for JVM tests. */
object TestItems {
    fun item(
        id: String,
        url: String = "https://shop.example.com/$id",
        name: String? = "상품 $id",
        category: ItemCategory = ItemCategory(),
        purpose: ItemPurpose = ItemPurpose(),
        requiredAction: RequiredAction = RequiredAction.CLASSIFICATION_REVIEW,
        analysis: AnalysisStatus = AnalysisStatus.READY,
        createdAt: Instant = Instant.fromEpochSeconds(1_800_000_000),
    ) = WishlistItem(
        id = id,
        clientSubmissionId = "sub-$id",
        version = 1,
        sourceUrl = url,
        product = ProductSnapshot(name = name),
        category = category,
        purpose = purpose,
        analysis = ItemAnalysis(analysis),
        reviewStatus = ReviewStatus.PENDING,
        lifecycleStatus = LifecycleStatus.ACTIVE,
        requiredAction = requiredAction,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
