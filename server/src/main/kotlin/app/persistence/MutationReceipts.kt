package app.persistence

import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject

enum class ReceiptTarget(val column: String) { CATEGORY("category_id"), PURPOSE("purpose_id") }
data class MutationReceipt(val fingerprint: String, val targetId: UUID)

/** Creation receipts are kept for the account lifetime. Callers hold the owner structure lock. */
object MutationReceipts {
    fun find(c: Connection, owner: UUID, operation: String, key: UUID, target: ReceiptTarget): MutationReceipt? = c.prepareStatement(
        "select request_fingerprint,${target.column} from mutation_receipts where owner_id=? and operation=? and idempotency_key=?",
    ).use { s ->
        s.setObject(1, owner); s.setString(2, operation); s.setObject(3, key)
        s.executeQuery().use { r -> if (r.next()) MutationReceipt(r.getString(1), r.getObject(2, UUID::class.java)) else null }
    }

    /** Counts successful receipts after the owner lock using one clock reading. Null means allowed. */
    fun retryAfterSeconds(c: Connection, owner: UUID, operation: String, maxSuccesses: Int, windowSeconds: Int): Int? = c.prepareStatement("""
        with instant as materialized (select clock_timestamp() as as_of)
        select case when count(*)>=? then greatest(1,ceil(extract(epoch from
            min(created_at)+(? * interval '1 second')-(select as_of from instant))))::integer end
        from mutation_receipts where owner_id=? and operation=?
            and created_at>(select as_of from instant)-(? * interval '1 second')
    """).use { s ->
        s.setInt(1, maxSuccesses); s.setInt(2, windowSeconds); s.setObject(3, owner); s.setString(4, operation); s.setInt(5, windowSeconds)
        s.executeQuery().use { r -> check(r.next()); r.getObject(1) as Int? }
    }

    fun save(c: Connection, owner: UUID, operation: String, key: UUID, fingerprint: String, target: ReceiptTarget, targetId: UUID) {
        c.prepareStatement("""insert into mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint,${target.column})
            values (?,?,?,?,?)""").use { s ->
            s.setObject(1, owner); s.setString(2, operation); s.setObject(3, key); s.setString(4, fingerprint); s.setObject(5, targetId)
            check(s.executeUpdate() == 1)
        }
    }

    fun fingerprint(canonical: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
