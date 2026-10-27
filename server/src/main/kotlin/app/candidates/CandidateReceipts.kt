package app.candidates

import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class StoredMove(val fingerprint: String, val result: CandidateMoveResult)

/** Move receipts with the result body (schema v1). Callers hold the owner structure lock. */
internal object CandidateReceipts {
    const val MOVE_OPERATION = "MOVE_CANDIDATES"
    private const val SCHEMA = 1

    fun find(c: Connection, owner: UUID, key: UUID): StoredMove? = c.prepareStatement(
        "select request_fingerprint, result_json::text from candidate_mutation_receipts where owner_id=? and operation=? and idempotency_key=?",
    ).use { s ->
        s.setObject(1, owner); s.setString(2, MOVE_OPERATION); s.setObject(3, key)
        s.executeQuery().use { r -> if (r.next()) StoredMove(r.getString(1), decode(r.getString(2))) else null }
    }

    fun save(c: Connection, owner: UUID, key: UUID, fingerprint: String, purpose: UUID, result: CandidateMoveResult) = c.prepareStatement("""
        insert into candidate_mutation_receipts(owner_id,operation,idempotency_key,request_fingerprint,purpose_id,result_json)
        values (?,?,?,?,?,?::jsonb)
    """.trimIndent()).use { s ->
        s.setObject(1, owner); s.setString(2, MOVE_OPERATION); s.setObject(3, key); s.setString(4, fingerprint); s.setObject(5, purpose)
        s.setString(6, encode(result).toString()); check(s.executeUpdate() == 1)
    }

    fun encode(result: CandidateMoveResult): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        putJsonArray("items") { result.items.forEach { addJsonObject { put("id", it.id.toString()); put("version", it.version); put("purposeId", it.purposeId.toString()) } } }
        putJsonArray("purposes") { result.purposes.forEach {
            addJsonObject { put("id", it.id.toString()); put("candidateCount", it.candidateCount); put("membershipVersion", it.membershipVersion) }
        } }
    }

    private fun decode(raw: String): CandidateMoveResult {
        val body = Json.parseToJsonElement(raw).jsonObject
        check(body.getValue("schema").jsonPrimitive.int == SCHEMA) { "unknown candidate receipt schema" }
        return CandidateMoveResult(
            body.getValue("items").jsonArray.map { it.jsonObject }.map {
                MovedItem(UUID.fromString(it.getValue("id").jsonPrimitive.content), it.getValue("version").jsonPrimitive.int,
                    UUID.fromString(it.getValue("purposeId").jsonPrimitive.content))
            },
            body.getValue("purposes").jsonArray.map { it.jsonObject }.map {
                MovedPurpose(UUID.fromString(it.getValue("id").jsonPrimitive.content), it.getValue("candidateCount").jsonPrimitive.long,
                    it.getValue("membershipVersion").jsonPrimitive.int)
            },
            replayed = true,
        )
    }
}
