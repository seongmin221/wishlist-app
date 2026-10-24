package app.http

import app.ai.TaxonomyCatalog
import app.candidates.CandidateFilter
import app.candidates.CandidateLimits
import app.candidates.CandidateMoveRequest
import app.candidates.MoveSelection
import app.category.CategoryRef
import app.category.PublicCategoryRegistry
import app.common.parseCanonicalUuid
import app.wishlist.ReadPosition
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID

data class CandidateQuery(val filter: CandidateFilter, val limit: Int, val after: ReadPosition?, val facets: Boolean)

sealed interface CandidateQueryParseResult {
    data class Valid(val query: CandidateQuery) : CandidateQueryParseResult
    data object InvalidQuery : CandidateQueryParseResult
    data object InvalidCursor : CandidateQueryParseResult
}

sealed interface CandidateMoveParseResult {
    data class Valid(val request: CandidateMoveRequest) : CandidateMoveParseResult
    /** Value errors name `expectedVersion`/`items`; structural errors (not an object, unknown or missing keys) carry none. */
    data class Invalid(val fields: Set<String>) : CandidateMoveParseResult
}

object CandidateRequestParser {
    private val queryKeys = setOf("categoryId", "parentId", "cursor", "limit", "includeCategoryFacets")
    private val moveKeys = setOf("expectedVersion", "items")
    private val selectionKeys = setOf("id", "expectedVersion", "expectedPurposeId")
    private val parents by lazy { PublicCategoryRegistry(TaxonomyCatalog.loadV1()).groups.map { it.id }.toSet() }

    fun query(owner: UUID, purpose: UUID, p: Parameters): CandidateQueryParseResult {
        if (!p.names().all { it in queryKeys && p.getAll(it)?.size == 1 && !p[it].isNullOrEmpty() }) return CandidateQueryParseResult.InvalidQuery
        if (p["categoryId"] != null && p["parentId"] != null) return CandidateQueryParseResult.InvalidQuery
        val filter = when {
            p["categoryId"] != null -> CategoryRef.parse(p["categoryId"]!!)?.let(CandidateFilter::Category)
            p["parentId"] != null -> p["parentId"]!!.takeIf { it in parents }?.let(CandidateFilter::Parent)
            else -> CandidateFilter.None
        } ?: return CandidateQueryParseResult.InvalidQuery
        val limit = p["limit"]?.let { value -> value.takeIf { it.matches(Regex("[1-9][0-9]{0,2}")) }?.toInt()?.takeIf { it <= CandidateLimits.MAX_PAGE } }
            ?: if (p["limit"] == null) CandidateLimits.DEFAULT_PAGE else return CandidateQueryParseResult.InvalidQuery
        val facets = when (p["includeCategoryFacets"]) {
            null, "false" -> false
            "true" -> true
            else -> return CandidateQueryParseResult.InvalidQuery
        }
        val after = p["cursor"]?.let { CandidateCursorCodec.decode(owner, purpose, filter, it) ?: return CandidateQueryParseResult.InvalidCursor }
        return CandidateQueryParseResult.Valid(CandidateQuery(filter, limit, after, facets))
    }

    fun move(raw: String): CandidateMoveParseResult {
        val body = parseJsonObject(raw) ?: return CandidateMoveParseResult.Invalid(emptySet())
        if (body.keys != moveKeys) return CandidateMoveParseResult.Invalid(emptySet())
        val invalid = sortedSetOf<String>()
        val version = body.positiveVersion("expectedVersion").also { if (it == null) invalid += "expectedVersion" }
        val selections = (body["items"] as? JsonArray)?.map { (it as? JsonObject)?.let(::selection) }
        val items = selections?.takeIf { list ->
            list.size in 1..CandidateLimits.MAX_MOVE && list.none { it == null } && list.map { it!!.id }.distinct().size == list.size
        }?.map { it!! }
        if (items == null) invalid += "items"
        return if (invalid.isEmpty()) CandidateMoveParseResult.Valid(CandidateMoveRequest(version!!, items!!)) else CandidateMoveParseResult.Invalid(invalid)
    }

    private fun selection(element: JsonObject): MoveSelection? {
        if (element.keys != selectionKeys) return null
        val id = parseCanonicalUuid(element["id"].strictString()) ?: return null
        val version = element.positiveVersion("expectedVersion") ?: return null
        val purpose = when (val value = element["expectedPurposeId"]) {
            JsonNull -> null
            else -> parseCanonicalUuid(value.strictString()) ?: return null
        }
        return MoveSelection(id, version, purpose)
    }
}
