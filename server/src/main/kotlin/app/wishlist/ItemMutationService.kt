package app.wishlist

import app.category.CategoryRef
import app.common.FieldChange
import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import app.purpose.PurposeMembership
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class ItemMutationException(
    val code: String,
    val currentVersion: Int? = null,
    val fields: Set<String> = emptySet(),
    val retryAfterSeconds: Int? = null,
) : RuntimeException(code)

/**
 * ITEM-04/07/08. One transaction per command: owner -> purposes (ID order) -> item -> media (ID order).
 * Checks run on the locked row in the spec's order: version, state, changed text, changed references.
 */
class ItemMutationService(private val dataSource: DataSource) {
    private val items = WishlistItemRepository(dataSource)

    fun edit(owner: UUID, itemId: UUID, expectedVersion: Int, changes: ItemChanges): WishlistItem =
        mutate(owner, itemId, expectedVersion, changes, ItemCommand.Edit, ItemAction.EDIT)

    fun completeManually(owner: UUID, itemId: UUID, expectedVersion: Int, changes: ItemChanges): WishlistItem {
        require(changes.name is FieldChange.Set && changes.category is FieldChange.Set)
        return mutate(owner, itemId, expectedVersion, changes, ItemCommand.ManualCompletion, ItemAction.MANUAL_COMPLETE)
    }

    fun decideReview(owner: UUID, itemId: UUID, expectedVersion: Int, decision: ReviewDecision, changes: ItemChanges): WishlistItem {
        require(changes.name == FieldChange.Keep && changes.brand == FieldChange.Keep && changes.media == FieldChange.Keep)
        return mutate(owner, itemId, expectedVersion, changes, ItemCommand.Review(decision), ItemAction.REVIEW)
    }

    private fun mutate(owner: UUID, itemId: UUID, expectedVersion: Int, changes: ItemChanges, command: ItemCommand,
                       required: ItemAction): WishlistItem = dataSource.inTransaction { c ->
        OwnerStructureLock.lock(c, owner)
        // Under the owner lock no other structure change or Worker result can move this item between the two reads.
        val peek = ItemMutationRepository.find(c, owner, itemId, lock = false)
            ?.takeUnless { it.lifecycle == LifecycleStatus.DELETED } ?: throw ItemMutationException("WISHLIST_ITEM_NOT_FOUND")
        ItemMutationRepository.lockPurposes(c, owner, listOfNotNull(peek.row.purposeId, (changes.purpose as? FieldChange.Set)?.value))
        val item = checkNotNull(ItemMutationRepository.find(c, owner, itemId, lock = true))
        if (item.version != expectedVersion) throw ItemMutationException("WISHLIST_ITEM_VERSION_CONFLICT", item.version)
        if (required !in WishlistItemPolicy.evaluate(item.state).allowedActions) {
            throw ItemMutationException("WISHLIST_ITEM_STATE_CONFLICT", item.version)
        }
        val plan = ItemChangePlan.plan(item.row, changes, command)
        val media = ItemMutationRepository.lockMedia(c, owner, listOfNotNull(item.row.mediaId, plan.mediaToLink))
        ItemTextRules.invalidFields(plan).takeIf { it.isNotEmpty() }?.let { throw ItemMutationException("INVALID_WISHLIST_ITEM_INPUT", fields = it) }
        validateReferences(c, owner, itemId, plan, media)
        if (!plan.changed) return@inTransaction current(c, owner, itemId)
        try {
            ItemMutationRepository.write(c, itemId, plan.next, command == ItemCommand.ManualCompletion)
        } catch (error: SQLException) {
            // The partial unique index is the last guard; the owner lock keeps this off the normal path.
            if (error.sqlState == "23505" && plan.mediaToLink != null) throw ItemMutationException("MEDIA_NOT_AVAILABLE")
            throw error
        }
        plan.purposeTransition?.let { (from, to) -> PurposeMembership.recordTransition(c, owner, from, to) }
        plan.mediaToRelease?.let { ItemMutationRepository.releaseMedia(c, it) }
        plan.mediaToLink?.let { ItemMutationRepository.linkMedia(c, it) }
        current(c, owner, itemId)
    }

    private fun validateReferences(c: Connection, owner: UUID, itemId: UUID, plan: ItemChangePlan, media: Map<UUID, String>) {
        (plan.categoryToCheck as? CategoryRef.Custom)?.let {
            if (!ItemMutationRepository.customCategoryAvailable(c, owner, it.id)) throw ItemMutationException("CATEGORY_NOT_AVAILABLE")
        }
        plan.purposeToCheck?.let {
            if (!ItemMutationRepository.purposeActive(c, owner, it)) throw ItemMutationException("PURPOSE_NOT_AVAILABLE")
        }
        plan.mediaToLink?.let {
            if (media[it] != "READY" || ItemMutationRepository.mediaUsedByOther(c, it, itemId)) {
                throw ItemMutationException("MEDIA_NOT_AVAILABLE")
            }
        }
    }

    private fun current(c: Connection, owner: UUID, itemId: UUID): WishlistItem = checkNotNull(items.findOwned(c, owner, itemId))
}
