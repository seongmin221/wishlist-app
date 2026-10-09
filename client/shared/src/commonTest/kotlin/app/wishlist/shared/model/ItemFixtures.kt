package app.wishlist.shared.model

import kotlin.time.Instant

internal val fixtureTime: Instant = Instant.parse("2026-10-07T00:00:00Z")

// Populate snapshots directly: policy is never used to generate expected fixture actions.
internal fun itemFixture(
    analysis: AnalysisStatus = AnalysisStatus.READY,
    review: ReviewStatus = ReviewStatus.CONFIRMED,
    lifecycle: LifecycleStatus = LifecycleStatus.ACTIVE,
    name: String? = "헤드폰",
    categoryId: String? = "C026",
    missingReason: CategoryMissingReason? = null,
    completed: Instant? = null,
    required: RequiredAction = RequiredAction.NONE,
    actions: Set<ItemAction> = setOf(ItemAction.EDIT, ItemAction.DELETE),
    version: Int = 1,
    id: String = "00000000-0000-0000-0000-000000000001",
    clientSubmissionId: String = "00000000-0000-0000-0000-000000000002",
) = WishlistItem(
    id = id,
    clientSubmissionId = clientSubmissionId,
    version = version,
    sourceUrl = "https://shop.example/item",
    product = ProductSnapshot(name = name),
    category = ItemCategory(id = categoryId, missingReason = missingReason),
    purpose = ItemPurpose(),
    analysis = ItemAnalysis(analysis),
    reviewStatus = review,
    lifecycleStatus = lifecycle,
    requiredAction = required,
    allowedActions = actions,
    createdAt = fixtureTime,
    updatedAt = fixtureTime,
    manualCompletionAt = completed,
)
