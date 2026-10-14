package app.wishlist

import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.ReceiptTarget
import app.persistence.inTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

sealed interface ReanalysisResult {
    val item: WishlistItem
    data class Started(override val item: WishlistItem) : ReanalysisResult
    data class Replayed(override val item: WishlistItem) : ReanalysisResult
}

/**
 * ITEM-06. Under the owner lock: receipt replay first (no state or limit checks), then the item, the allowed action and
 * the per-owner limit. The new generation's item, job, outbox and receipt commit together; dispatch runs after commit.
 */
class ReanalysisService(
    private val dataSource: DataSource,
    private val dispatchAfterCommit: (UUID) -> Unit = {},
) {
    private val items = WishlistItemRepository(dataSource)

    fun reanalyze(owner: UUID, itemId: UUID, key: UUID): ReanalysisResult {
        val (result, eventId) = dataSource.inTransaction { c ->
            OwnerStructureLock.lock(c, owner)
            val fingerprint = MutationReceipts.fingerprint(JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString()))))
            MutationReceipts.find(c, owner, OPERATION, key, ReceiptTarget.WISHLIST_ITEM)?.let { receipt ->
                if (receipt.fingerprint != fingerprint || receipt.targetId != itemId) throw ItemMutationException("IDEMPOTENCY_KEY_REUSED")
                val current = items.findOwned(c, owner, itemId)
                    ?.takeUnless { it.storedState.state.lifecycleStatus == LifecycleStatus.DELETED }
                    ?: throw ItemMutationException("WISHLIST_ITEM_NOT_FOUND")
                return@inTransaction ReanalysisResult.Replayed(DeletionImpacts.attach(c, owner, current)) to null
            }
            ItemMutationRepository.find(c, owner, itemId, lock = false)
                ?.takeUnless { it.lifecycle == LifecycleStatus.DELETED } ?: throw ItemMutationException("WISHLIST_ITEM_NOT_FOUND")
            val item = checkNotNull(ItemMutationRepository.find(c, owner, itemId, lock = true))
            val jobs = ItemMutationRepository.lockJobs(c, itemId)
            if (ItemAction.REANALYZE !in WishlistItemPolicy.evaluate(item.state).allowedActions) {
                throw ItemMutationException("WISHLIST_ITEM_STATE_CONFLICT", item.version)
            }
            MutationReceipts.retryAfterSeconds(c, owner, OPERATION, WishlistItemLimits.REANALYSIS_MAX_SUCCESSES,
                WishlistItemLimits.REANALYSIS_WINDOW_SECONDS)?.let {
                throw ItemMutationException("WISHLIST_ITEM_REANALYSIS_RATE_LIMITED", retryAfterSeconds = it)
            }
            // A FAILED_RETRYABLE item normally has no live job; an execution left running must not outlive its generation.
            jobs.filter { (_, stage) -> stage in ItemMutationService.IN_PROGRESS_STAGES }.forEach { (job, _) -> ItemMutationRepository.cancelJob(c, job) }
            val generation = item.generation + 1
            ItemMutationRepository.startGeneration(c, itemId, generation)
            val event = items.insertAnalysis(c, itemId, generation)
            MutationReceipts.save(c, owner, OPERATION, key, fingerprint, ReceiptTarget.WISHLIST_ITEM, itemId)
            ReanalysisResult.Started(DeletionImpacts.attach(c, owner, checkNotNull(items.findOwned(c, owner, itemId)))) to event
        }
        if (eventId != null) {
            try { dispatchAfterCommit(eventId) } catch (cause: Exception) {
                // Cancellation keeps its meaning; the committed attempt is recovered by a same-key replay and maintenance.
                if (cause is CancellationException) throw cause
                logger.warn("Post-commit dispatch failed eventId={} exceptionType={}", eventId, cause.javaClass.name)
            }
        }
        return result
    }

    private companion object {
        const val OPERATION = "REANALYZE_WISHLIST_ITEM"
        val logger = LoggerFactory.getLogger(ReanalysisService::class.java)
    }
}
