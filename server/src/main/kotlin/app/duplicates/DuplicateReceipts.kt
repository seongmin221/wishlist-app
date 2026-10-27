package app.duplicates

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class StoredDecision(val fingerprint: String, val result: DuplicateDecisionResult)

/** DUP-02 receipts with the result body (schema v1), kept for the account lifetime. Callers hold the owner lock. */
internal object DuplicateReceipts {
    private const val SCHEMA = 1

    fun find(c: Connection, owner: UUID, key: UUID): StoredDecision? = c.prepareStatement(
        "select request_fingerprint, result_json::text from duplicate_decision_receipts where owner_id=? and idempotency_key=?",
    ).use { s ->
        s.setObject(1, owner); s.setObject(2, key)
        s.executeQuery().use { r -> if (r.next()) StoredDecision(r.getString(1), decode(r.getString(2))) else null }
    }

    fun save(c: Connection, owner: UUID, key: UUID, fingerprint: String, candidate: UUID, result: DuplicateDecisionResult) = c.prepareStatement("""
        insert into duplicate_decision_receipts(owner_id, idempotency_key, request_fingerprint, candidate_id, result_json)
        values (?,?,?,?,?::jsonb)
    """.trimIndent()).use { s ->
        s.setObject(1, owner); s.setObject(2, key); s.setString(3, fingerprint); s.setObject(4, candidate)
        s.setString(5, encode(result).toString()); check(s.executeUpdate() == 1)
    }

    private fun encode(result: DuplicateDecisionResult): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("candidateId", result.candidateId.toString()); put("decision", result.decision.name)
        put("decidedAt", result.decidedAt.toString()); put("candidateVersion", result.candidateVersion)
        put("deletedItemId", result.deletedItemId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        putJsonArray("remainingItemIds") { result.remainingItemIds.forEach { add(JsonPrimitive(it.toString())) } }
        putJsonArray("purposes") { result.purposes.forEach {
            addJsonObject { put("id", it.id.toString()); put("candidateCount", it.candidateCount); put("membershipVersion", it.membershipVersion) }
        } }
    }

    private fun decode(raw: String): DuplicateDecisionResult {
        val body = Json.parseToJsonElement(raw).jsonObject
        check(body.getValue("schema").jsonPrimitive.int == SCHEMA) { "unknown duplicate receipt schema" }
        return DuplicateDecisionResult(
            UUID.fromString(body.getValue("candidateId").jsonPrimitive.content),
            DuplicateDecision.valueOf(body.getValue("decision").jsonPrimitive.content),
            Instant.parse(body.getValue("decidedAt").jsonPrimitive.content),
            body.getValue("candidateVersion").jsonPrimitive.int,
            (body.getValue("deletedItemId") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let(UUID::fromString),
            body.getValue("remainingItemIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) },
            body.getValue("purposes").jsonArray.map { it.jsonObject }.map {
                DuplicatePurposeCount(UUID.fromString(it.getValue("id").jsonPrimitive.content), it.getValue("candidateCount").jsonPrimitive.long,
                    it.getValue("membershipVersion").jsonPrimitive.int)
            },
            replayed = true,
        )
    }
}
