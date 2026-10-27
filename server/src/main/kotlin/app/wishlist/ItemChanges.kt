package app.wishlist

import app.category.CategoryRef
import app.common.FieldChange
import app.text.UserTextRules
import java.util.UUID

object WishlistItemLimits {
    const val TEXT_MAX = 200
    const val REANALYSIS_MAX_SUCCESSES = 10
    const val REANALYSIS_WINDOW_SECONDS = 60
}

enum class ReviewDecision { CONFIRM, DEFER }

/** User changes shared by ITEM-04/07/08. Keep leaves the stored value; Set(null) clears an optional value. */
data class ItemChanges(
    val name: FieldChange<String> = FieldChange.Keep,
    val brand: FieldChange<String?> = FieldChange.Keep,
    val category: FieldChange<CategoryRef> = FieldChange.Keep,
    val purpose: FieldChange<UUID?> = FieldChange.Keep,
    val media: FieldChange<UUID?> = FieldChange.Keep,
)

sealed interface ItemCommand {
    data object Edit : ItemCommand
    data object ManualCompletion : ItemCommand
    data class Review(val decision: ReviewDecision) : ItemCommand
}

/** The user-editable part of a stored item. [mediaId] is the raw reference, whatever the media state. */
data class ItemRow(
    val name: String?,
    val nameSource: ValueSource?,
    val brand: String?,
    val publicCategoryId: String?,
    val customCategoryId: UUID?,
    val categorySource: ValueSource?,
    val categoryMissingReason: CategoryMissingReason?,
    val purposeId: UUID?,
    val purposeSource: ValueSource,
    val mediaId: UUID?,
    val imageSource: ValueSource?,
    val productImageUrl: String?,
    val overrides: Set<String>,
    val reviewStatus: ReviewStatus,
)

/**
 * The new row for one command and the checks it still needs. Only values that differ from the stored ones are
 * validated (text rules, references), so an unchanged extracted value never blocks a save.
 */
data class ItemChangePlan(
    val current: ItemRow,
    val next: ItemRow,
    val textFields: Set<String>,
    val categoryToCheck: CategoryRef?,
    val purposeToCheck: UUID?,
    val mediaToLink: UUID?,
    val mediaToRelease: UUID?,
    /** (from, to) when the purpose membership changes. */
    val purposeTransition: Pair<UUID?, UUID?>?,
) {
    val changed: Boolean get() = next != current

    companion object {
        fun plan(current: ItemRow, changes: ItemChanges, command: ItemCommand): ItemChangePlan {
            val forceUser = command == ItemCommand.ManualCompletion
            var next = current
            val text = mutableSetOf<String>()

            (changes.name as? FieldChange.Set)?.value?.let { name ->
                if (name != current.name) text += "name"
                if (name != current.name || forceUser) {
                    next = next.copy(name = name, nameSource = ValueSource.USER, overrides = next.overrides + "NAME")
                }
            }
            (changes.brand as? FieldChange.Set)?.let { change ->
                if (change.value != current.brand) {
                    if (change.value != null) text += "brand"
                    next = next.copy(brand = change.value, overrides = next.overrides + "BRAND")
                }
            }
            var categoryToCheck: CategoryRef? = null
            var categoryChanged = false
            (changes.category as? FieldChange.Set)?.value?.let { category ->
                val same = when (category) {
                    is CategoryRef.Public -> current.publicCategoryId == category.value && current.customCategoryId == null
                    is CategoryRef.Custom -> current.customCategoryId == category.id && current.publicCategoryId == null
                }
                if (!same) { categoryToCheck = category; categoryChanged = true }
                if (!same || forceUser) {
                    next = next.copy(
                        publicCategoryId = (category as? CategoryRef.Public)?.value,
                        customCategoryId = (category as? CategoryRef.Custom)?.id,
                        categorySource = ValueSource.USER, categoryMissingReason = null,
                    )
                }
            }
            var purposeToCheck: UUID? = null
            var purposeTransition: Pair<UUID?, UUID?>? = null
            var purposeChanged = false
            (changes.purpose as? FieldChange.Set)?.let { change ->
                val target = change.value
                if (target != current.purposeId || current.purposeSource != ValueSource.USER && target == null) {
                    if (target != current.purposeId) {
                        purposeToCheck = target
                        purposeTransition = current.purposeId to target
                    }
                    purposeChanged = true
                    next = next.copy(purposeId = target, purposeSource = ValueSource.USER)
                }
            }
            var mediaToLink: UUID? = null
            var mediaToRelease: UUID? = null
            (changes.media as? FieldChange.Set)?.let { change ->
                val target = change.value
                if (target != current.mediaId) {
                    mediaToRelease = current.mediaId
                    mediaToLink = target
                    next = if (target != null) next.copy(mediaId = target, imageSource = ValueSource.USER)
                    else next.copy(mediaId = null, imageSource = if (current.productImageUrl == null) null else ValueSource.AI,
                        overrides = next.overrides - "IMAGE")
                }
            }
            val review = when (command) {
                ItemCommand.Edit -> if (categoryChanged || purposeChanged) ReviewAfterEdit.status(next) else next.reviewStatus
                ItemCommand.ManualCompletion -> ReviewStatus.CONFIRMED
                is ItemCommand.Review -> if (command.decision == ReviewDecision.CONFIRM) ReviewStatus.CONFIRMED else ReviewStatus.DEFERRED
            }
            next = next.copy(reviewStatus = review)
            return ItemChangePlan(current, next, text, categoryToCheck, purposeToCheck, mediaToLink, mediaToRelease, purposeTransition)
        }
    }
}

/** Decision 3: an edit confirms PENDING/DEFERRED items once no AI category or AI purpose connection remains. */
object ReviewAfterEdit {
    fun status(row: ItemRow): ReviewStatus {
        if (row.reviewStatus !in setOf(ReviewStatus.PENDING, ReviewStatus.DEFERRED)) return row.reviewStatus
        val aiCategory = row.categorySource == ValueSource.AI && (row.publicCategoryId != null || row.customCategoryId != null)
        val aiPurpose = row.purposeSource == ValueSource.AI && row.purposeId != null
        return if (aiCategory || aiPurpose) row.reviewStatus else ReviewStatus.CONFIRMED
    }
}

object ItemTextRules {
    fun valid(text: String): Boolean = UserTextRules.valid(text, WishlistItemLimits.TEXT_MAX) && !UserTextRules.isBlank(text)

    /** Changed name/brand values that break the shared single-line user text rules, sorted for error details. */
    fun invalidFields(plan: ItemChangePlan): Set<String> = plan.textFields.filterTo(sortedSetOf()) { field ->
        val value = if (field == "name") plan.next.name else plan.next.brand
        value == null || !valid(value)
    }
}
