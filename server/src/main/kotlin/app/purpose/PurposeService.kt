package app.purpose

import app.persistence.MutationReceipts
import app.persistence.OwnerStructureLock
import app.persistence.ReceiptTarget
import app.persistence.inTransaction
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PurposeService(private val dataSource: DataSource) {
    private val purposes = PurposeRepository()

    fun create(owner: UUID, key: UUID, input: PurposeInput): PurposeCreation {
        validate(input)
        val fingerprint = MutationReceipts.fingerprint(JsonObject(linkedMapOf(
            "name" to JsonPrimitive(input.name), "description" to (input.description?.let(::JsonPrimitive) ?: JsonNull),
            "colorKey" to JsonPrimitive(input.color.name), "iconKey" to JsonPrimitive(input.icon.name),
        )))
        return dataSource.inTransaction { c ->
            OwnerStructureLock.lock(c, owner)
            val receipt = MutationReceipts.find(c, owner, CREATE_OPERATION, key, ReceiptTarget.PURPOSE)
            if (receipt != null) {
                if (receipt.fingerprint != fingerprint) throw PurposeException("IDEMPOTENCY_KEY_REUSED")
                val current = purposes.find(c, owner, receipt.targetId) ?: throw PurposeException("PURPOSE_NOT_AVAILABLE")
                return@inTransaction PurposeCreation(current, purposes.count(c, owner, "ACTIVE"), replayed = true)
            }
            MutationReceipts.retryAfterSeconds(c, owner, CREATE_OPERATION, PurposeLimits.CREATES_PER_WINDOW, PurposeLimits.CREATE_WINDOW_SECONDS)
                ?.let { throw PurposeException("PURPOSE_CREATE_RATE_LIMITED", retryAfterSeconds = it) }
            val active = purposes.count(c, owner, "ACTIVE")
            if (active >= PurposeLimits.ACTIVE_LIMIT) throw PurposeException("PURPOSE_LIMIT_REACHED")
            val id = purposes.insert(c, owner, input)
            MutationReceipts.save(c, owner, CREATE_OPERATION, key, fingerprint, ReceiptTarget.PURPOSE, id)
            PurposeCreation(checkNotNull(purposes.find(c, owner, id)), active + 1, replayed = false)
        }
    }

    fun get(owner: UUID, id: UUID): Purpose? = dataSource.inTransaction(readOnly = true) { purposes.find(it, owner, id) }

    fun list(owner: UUID, projection: PurposeProjection, limit: Int, after: PurposeCursorPosition?): PurposePage {
        require(limit in 1..PurposeLimits.PAGE_LIMIT)
        return dataSource.inTransaction(readOnly = true) { c ->
            val rows = purposes.page(c, owner, after, limit + 1)
            val page = rows.take(limit)
            PurposePage(
                projection, purposes.entries(c,owner,page,includePreviews=projection==PurposeProjection.SUMMARY),
                if (rows.size > limit) page.last().let { PurposeCursorPosition(it.activityAt, it.id) } else null,
                purposes.count(c, owner, "ACTIVE"), purposes.count(c, owner, "ARCHIVED"),
            )
        }
    }

    fun patch(owner: UUID, id: UUID, expectedVersion: Int, changes: PurposeChanges): Purpose = dataSource.inTransaction { c ->
        OwnerStructureLock.lock(c, owner)
        val current = purposes.find(c, owner, id, lock = true) ?: throw PurposeException("PURPOSE_NOT_FOUND")
        if (current.version != expectedVersion) throw PurposeException("PURPOSE_VERSION_CONFLICT", currentVersion = current.version)
        if (!changes.hasChanges) throw PurposeException("INVALID_PURPOSE_INPUT")
        val input = changes.applyTo(current.input)
        validate(input)
        if (input == current.input) return@inTransaction current
        purposes.update(c, owner, id, input)
        checkNotNull(purposes.find(c, owner, id))
    }

    private fun validate(input: PurposeInput) {
        val fields = PurposeInputPolicy.validate(input.name, input.description)
        if (fields.isNotEmpty()) throw PurposeException("INVALID_PURPOSE_INPUT", fields)
    }

    private companion object { const val CREATE_OPERATION = "CREATE_PURPOSE" }
}
