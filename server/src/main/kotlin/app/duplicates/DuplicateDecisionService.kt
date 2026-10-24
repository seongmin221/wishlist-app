package app.duplicates

import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import app.wishlist.ItemAction
import app.wishlist.ItemMutationRepository
import app.wishlist.ItemMutationService
import app.wishlist.LifecycleStatus
import app.wishlist.WishlistItemPolicy
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** [expectedItemVersion] is the version of the item the decision deletes; KEEP_BOTH carries none. */
data class DuplicateDecisionRequest(
    val candidateId: UUID,
    val decision: DuplicateDecision,
    val expectedCandidateVersion: Int,
    val expectedItemVersion: Int?,
) {
    init { require((decision == DuplicateDecision.KEEP_BOTH) == (expectedItemVersion == null)) }
}

data class DuplicatePurposeCount(val id: UUID, val candidateCount: Long, val membershipVersion: Int)

data class DuplicateDecisionResult(
    val candidateId: UUID,
    val decision: DuplicateDecision,
    val decidedAt: Instant,
    val candidateVersion: Int,
    val deletedItemId: UUID?,
    val remainingItemIds: List<UUID>,
    val purposes: List<DuplicatePurposeCount>,
    val replayed: Boolean = false,
)

/**
 * DUP-02. The decision, the chosen deletion and the receipt commit together. Lock order: owner -> receipt -> pair ->
 * the deleted item's purpose -> both items (ID order) -> media -> jobs, matching ITEM-05 after the pair row.
 */
class DuplicateDecisionService(
    private val dataSource: DataSource,
    /** Test seam: runs after every write, before commit. */
    private val beforeCommit: () -> Unit = {},
) {
    private data class LockedPair(val id: UUID, val newItem: UUID, val existingItem: UUID, val bases: Set<DuplicateBasis>,
                                  val status: String, val version: Int)

    fun decide(owner: UUID, itemId: UUID, key: UUID, request: DuplicateDecisionRequest): DuplicateDecisionResult = dataSource.inTransaction { c ->
        OwnerStructureLock.lock(c, owner)
        val fingerprint = fingerprint(itemId, request)
        DuplicateReceipts.find(c, owner, key)?.let { stored ->
            if (stored.fingerprint != fingerprint) throw DuplicateException("IDEMPOTENCY_KEY_REUSED")
            return@inTransaction stored.result
        }
        // Under the owner lock no other writer can move these rows between the reads below and the row locks.
        val newItem = ItemMutationRepository.find(c, owner, itemId, lock = false)?.takeUnless { it.lifecycle == LifecycleStatus.DELETED }
            ?: throw DuplicateException("WISHLIST_ITEM_NOT_FOUND")
        val pair = lockPair(c, owner, itemId, request.candidateId)?.takeUnless { it.status == "WITHDRAWN" }
            ?: throw DuplicateException("DUPLICATE_CANDIDATE_NOT_FOUND")
        val existingItem = ItemMutationRepository.find(c, owner, pair.existingItem, lock = false)
        if (newItem.lifecycle != LifecycleStatus.ACTIVE || existingItem?.lifecycle != LifecycleStatus.ACTIVE) {
            throw DuplicateException("DUPLICATE_CANDIDATE_NOT_FOUND")
        }
        if (pair.version != request.expectedCandidateVersion) throw DuplicateException("DUPLICATE_CANDIDATE_VERSION_CONFLICT", pair.version)
        val kind = DuplicateKinds.kindOf(newItem.state.analysisStatus.name, canonical(c, itemId), existingItem.state.analysisStatus.name, pair.bases)
        if (pair.status != "OPEN" || request.decision !in DuplicateKinds.allowedDecisions(kind)) {
            throw DuplicateException("DUPLICATE_DECISION_NOT_ALLOWED", pair.version)
        }
        val deleted = when (request.decision) {
            DuplicateDecision.KEEP_BOTH -> null
            DuplicateDecision.DELETE_NEW -> newItem
            DuplicateDecision.DELETE_EXISTING -> existingItem
        }
        ItemMutationRepository.lockPurposes(c, owner, listOfNotNull(deleted?.row?.purposeId))
        val locked = ItemMutationRepository.lockAll(c, owner, listOf(pair.newItem, pair.existingItem)).associateBy { it.id }
        val target = deleted?.let { locked.getValue(it.id) }
        if (target != null) {
            val jobs = ItemMutationService.lockForDelete(c, owner, target)
            if (target.version != request.expectedItemVersion) throw DuplicateException("WISHLIST_ITEM_VERSION_CONFLICT", target.version, target.id)
            if (ItemAction.DELETE !in WishlistItemPolicy.evaluate(target.state).allowedActions) {
                throw DuplicateException("WISHLIST_ITEM_STATE_CONFLICT", target.version, target.id)
            }
            ItemMutationService.deleteLocked(c, owner, target, jobs)
        }
        val (decidedAt, version) = recordDecision(c, pair.id, request.decision)
        val result = DuplicateDecisionResult(pair.id, request.decision, decidedAt, version, target?.id,
            listOf(pair.newItem, pair.existingItem).filter { it != target?.id }.sortedBy { it.toString() },
            listOfNotNull(target?.row?.purposeId).map { purposeCount(c, owner, it) })
        DuplicateReceipts.save(c, owner, key, fingerprint, pair.id, result)
        beforeCommit()
        result
    }

    private fun lockPair(c: Connection, owner: UUID, newItem: UUID, id: UUID): LockedPair? = c.prepareStatement("""
        select id, new_item_id, existing_item_id, bases, status, version from duplicate_candidates
        where owner_id=? and new_item_id=? and id=? for update
    """.trimIndent()).use { s ->
        s.setObject(1, owner); s.setObject(2, newItem); s.setObject(3, id)
        s.executeQuery().use { r ->
            if (!r.next()) null else LockedPair(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getObject(3, UUID::class.java),
                (r.getArray(4).array as Array<*>).map { DuplicateBasis.valueOf(it as String) }.toSet(), r.getString(5), r.getInt(6))
        }
    }

    private fun canonical(c: Connection, itemId: UUID): String? =
        c.prepareStatement("select canonical_url from wishlist_items where id=?").use { s ->
            s.setObject(1, itemId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }

    private fun recordDecision(c: Connection, pair: UUID, decision: DuplicateDecision): Pair<Instant, Int> = c.prepareStatement("""
        update duplicate_candidates set status='DECIDED', decision=?, decided_at=clock_timestamp(), version=version+1, updated_at=clock_timestamp()
        where id=? returning decided_at, version
    """.trimIndent()).use { s ->
        s.setString(1, decision.name); s.setObject(2, pair)
        s.executeQuery().use { r -> check(r.next()); r.getTimestamp(1).toInstant() to r.getInt(2) }
    }

    private fun purposeCount(c: Connection, owner: UUID, purpose: UUID): DuplicatePurposeCount = c.prepareStatement("""
        select p.membership_version, (select count(*) from wishlist_items i
            where i.owner_id=p.owner_id and i.purpose_id=p.id and i.lifecycle_status='ACTIVE')
        from purposes p where p.owner_id=? and p.id=?
    """.trimIndent()).use { s ->
        s.setObject(1, owner); s.setObject(2, purpose)
        s.executeQuery().use { r -> check(r.next()); DuplicatePurposeCount(purpose, r.getLong(2), r.getInt(1)) }
    }

    /** Request identity: the path item and the whole body. */
    private fun fingerprint(itemId: UUID, r: DuplicateDecisionRequest): String = MessageDigest.getInstance("SHA-256")
        .digest("v1|$itemId|${r.candidateId}|${r.decision}|${r.expectedCandidateVersion}|${r.expectedItemVersion ?: "-"}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
