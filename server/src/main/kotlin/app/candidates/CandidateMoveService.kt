package app.candidates

import app.common.FieldChange
import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import app.purpose.PurposeMembership
import app.wishlist.ItemAction
import app.wishlist.ItemChangePlan
import app.wishlist.ItemChanges
import app.wishlist.ItemCommand
import app.wishlist.ItemMutationRepository
import app.wishlist.LifecycleStatus
import app.wishlist.WishlistItemPolicy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PUR-08. One transaction: owner -> receipt -> target and selection existence -> purposes (SQL ID order) -> items (SQL ID
 * order) -> target version -> per item version, state, previous purpose. Any conflict rolls back items, purposes and receipt.
 */
class CandidateMoveService(
    private val dataSource: DataSource,
    /** Test seam: runs while the move holds its item row locks. */
    private val afterItemLock: () -> Unit = {},
) {
    fun move(owner: UUID, purpose: UUID, key: UUID, request: CandidateMoveRequest): CandidateMoveResult {
        val ids = request.items.map { it.id }
        require(ids.size in 1..CandidateLimits.MAX_MOVE && ids.distinct().size == ids.size)
        val fingerprint = fingerprint(purpose, request)
        return dataSource.inTransaction { c ->
            OwnerStructureLock.lock(c, owner)
            CandidateReceipts.find(c, owner, key)?.let { stored ->
                if (stored.fingerprint != fingerprint) throw CandidateException("IDEMPOTENCY_KEY_REUSED")
                return@inTransaction stored.result
            }
            targetVersion(c, owner, purpose) ?: throw CandidateException("PURPOSE_NOT_FOUND")
            // Existence of the whole selection comes first, so a foreign or missing item hides every other conflict.
            val current = livePurposes(c, owner, ids)
            if (current.size != ids.size) throw CandidateException("WISHLIST_ITEM_NOT_FOUND")
            ItemMutationRepository.lockPurposes(c, owner, current.values.filterNotNull() + purpose)
            val items = ItemMutationRepository.lockAll(c, owner, ids)
            afterItemLock()
            if (items.size != ids.size || items.any { it.lifecycle == LifecycleStatus.DELETED }) throw CandidateException("WISHLIST_ITEM_NOT_FOUND")
            val version = targetVersion(c, owner, purpose) ?: throw CandidateException("PURPOSE_NOT_FOUND")
            if (version != request.expectedVersion) throw CandidateException("PURPOSE_VERSION_CONFLICT", currentVersion = version)
            val selections = request.items.associateBy { it.id }
            for (item in items) {
                val selection = selections.getValue(item.id)
                if (item.version != selection.expectedVersion) conflict("WISHLIST_ITEM_VERSION_CONFLICT", item.id, item.version)
                if (ItemAction.EDIT !in WishlistItemPolicy.evaluate(item.state).allowedActions || item.row.purposeId == purpose) {
                    conflict("WISHLIST_ITEM_STATE_CONFLICT", item.id, item.version)
                }
                if (item.row.purposeId != selection.expectedPurposeId) conflict("WISHLIST_ITEM_PURPOSE_CONFLICT", item.id, item.version)
            }
            for (item in items) {
                val plan = ItemChangePlan.plan(item.row, ItemChanges(purpose = FieldChange.Set(purpose)), ItemCommand.Edit)
                ItemMutationRepository.write(c, item.id, plan.next, completeManually = false)
                PurposeMembership.recordTransition(c, owner, item.row.purposeId, purpose)
            }
            val touched = (items.mapNotNull { it.row.purposeId } + purpose).distinct().sortedWith(SQL_UUID_ORDER)
            val result = CandidateMoveResult(
                items.map { MovedItem(it.id, it.version + 1, purpose) }.sortedWith(compareBy(SQL_UUID_ORDER) { it.id }),
                touched.map { membership(c, owner, it) }, replayed = false,
            )
            CandidateReceipts.save(c, owner, key, fingerprint, purpose, result)
            result
        }
    }

    private fun conflict(code: String, item: UUID, version: Int): Nothing = throw CandidateException(code, item, version)

    private fun targetVersion(c: Connection, owner: UUID, purpose: UUID): Int? =
        c.prepareStatement("select version from purposes where owner_id=? and id=? and lifecycle_status='ACTIVE'").use { s ->
            s.setObject(1, owner); s.setObject(2, purpose); s.executeQuery().use { r -> if (r.next()) r.getInt(1) else null }
        }

    /** Current purpose of each selected owner item that is not a DELETED tombstone. */
    private fun livePurposes(c: Connection, owner: UUID, ids: List<UUID>): Map<UUID, UUID?> = c.prepareStatement(
        "select id, purpose_id from wishlist_items where owner_id=? and id = any(?) and lifecycle_status<>'DELETED'",
    ).use { s ->
        s.setObject(1, owner); s.setArray(2, c.createArrayOf("uuid", ids.toTypedArray()))
        s.executeQuery().use { r -> buildMap { while (r.next()) put(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java)) } }
    }

    private fun membership(c: Connection, owner: UUID, purpose: UUID): MovedPurpose = c.prepareStatement("""
        select p.membership_version, (select count(*) from wishlist_items i where i.owner_id=p.owner_id and i.purpose_id=p.id
            and i.lifecycle_status='ACTIVE') from purposes p where p.owner_id=? and p.id=?
    """.trimIndent()).use { s ->
        s.setObject(1, owner); s.setObject(2, purpose)
        s.executeQuery().use { r -> check(r.next()); MovedPurpose(purpose, r.getLong(2), r.getInt(1)) }
    }

    /** Selection order carries no meaning: the fingerprint lists items in SQL UUID order. */
    private fun fingerprint(purpose: UUID, request: CandidateMoveRequest): String = MutationReceipts.fingerprint(JsonObject(linkedMapOf(
        "purposeId" to JsonPrimitive(purpose.toString()), "expectedVersion" to JsonPrimitive(request.expectedVersion),
        "items" to JsonArray(request.items.sortedWith(compareBy(SQL_UUID_ORDER) { it.id }).map {
            JsonObject(linkedMapOf("id" to JsonPrimitive(it.id.toString()), "expectedVersion" to JsonPrimitive(it.expectedVersion),
                "expectedPurposeId" to (it.expectedPurposeId?.let { p -> JsonPrimitive(p.toString()) } ?: JsonNull)))
        }),
    )))
}
