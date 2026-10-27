package app.http

import app.common.parseCanonicalUuid
import app.duplicates.DuplicateDecision
import app.duplicates.DuplicateDecisionRequest
import app.duplicates.DuplicateLimits
import app.wishlist.ReadPosition
import io.ktor.http.Parameters
import java.util.UUID

data class DuplicateQuery(val limit: Int, val after: ReadPosition?)

sealed interface DuplicateQueryParseResult {
    data class Valid(val query: DuplicateQuery) : DuplicateQueryParseResult
    data object InvalidQuery : DuplicateQueryParseResult
    data object InvalidCursor : DuplicateQueryParseResult
}

sealed interface DuplicateDecisionParseResult {
    data class Valid(val request: DuplicateDecisionRequest) : DuplicateDecisionParseResult
    /** Empty [fields] means a structural error (not an object, unknown or missing keys). */
    data class Invalid(val fields: Set<String>) : DuplicateDecisionParseResult
}

object DuplicateRequestParser {
    private val required = setOf("candidateId", "decision", "expectedCandidateVersion")

    /** expectedItemVersion belongs to the deleting decisions only, so its presence is a value rule, not structure. */
    fun decision(raw: String): DuplicateDecisionParseResult {
        val body = parseJsonObject(raw) ?: return DuplicateDecisionParseResult.Invalid(emptySet())
        if (!body.keys.containsAll(required) || !(required + "expectedItemVersion").containsAll(body.keys)) {
            return DuplicateDecisionParseResult.Invalid(emptySet())
        }
        val invalid = sortedSetOf<String>()
        val candidate = parseCanonicalUuid(body["candidateId"].strictString()).also { if (it == null) invalid += "candidateId" }
        val decision = body["decision"].strictString()?.let { name -> DuplicateDecision.entries.firstOrNull { it.name == name } }
            .also { if (it == null) invalid += "decision" }
        val version = body.positiveVersion("expectedCandidateVersion").also { if (it == null) invalid += "expectedCandidateVersion" }
        val itemVersion = if ("expectedItemVersion" in body) body.positiveVersion("expectedItemVersion") else null
        val itemVersionValid = when (decision) {
            null -> "expectedItemVersion" !in body || itemVersion != null
            DuplicateDecision.KEEP_BOTH -> "expectedItemVersion" !in body
            else -> itemVersion != null
        }
        if (!itemVersionValid) invalid += "expectedItemVersion"
        return if (invalid.isEmpty()) DuplicateDecisionParseResult.Valid(DuplicateDecisionRequest(candidate!!, decision!!, version!!, itemVersion))
            else DuplicateDecisionParseResult.Invalid(invalid)
    }

    fun query(owner: UUID, item: UUID, p: Parameters): DuplicateQueryParseResult {
        if (!p.names().all { it in setOf("cursor", "limit") && p.getAll(it)?.size == 1 && !p[it].isNullOrEmpty() }) return DuplicateQueryParseResult.InvalidQuery
        val limit = p["limit"].let { value ->
            if (value == null) DuplicateLimits.DEFAULT_PAGE
            else value.takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toInt()?.takeIf { it <= DuplicateLimits.MAX_PAGE }
        } ?: return DuplicateQueryParseResult.InvalidQuery
        val after = p["cursor"]?.let { CandidateCursorCodec.decodeDuplicates(owner, item, it) ?: return DuplicateQueryParseResult.InvalidCursor }
        return DuplicateQueryParseResult.Valid(DuplicateQuery(limit, after))
    }
}
