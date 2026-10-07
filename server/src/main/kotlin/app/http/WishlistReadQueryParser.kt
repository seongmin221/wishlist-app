package app.http

import app.category.CategoryRef
import app.common.parseCanonicalUuid
import app.wishlist.*
import io.ktor.http.Parameters
import java.util.UUID

sealed interface ReadQueryParseResult {
    data class Valid(val query: ReadQuery) : ReadQueryParseResult
    data object InvalidQuery : ReadQueryParseResult
    data object InvalidCursor : ReadQueryParseResult
}

object WishlistReadQueryParser {
    private val windowKeys = setOf("cursor", "limit", "anchor", "before", "after")
    fun wishlist(owner: UUID, parameters: Parameters): ReadQueryParseResult {
        if (!validKeys(parameters, windowKeys + setOf("categoryId", "purposeId", "purposeUnassigned"))) return ReadQueryParseResult.InvalidQuery
        if (listOf("categoryId", "purposeId", "purposeUnassigned").count { parameters[it] != null } != 1) return ReadQueryParseResult.InvalidQuery
        val scope = when {
            parameters["categoryId"] != null -> CategoryRef.parse(parameters["categoryId"]!!)?.let(ReadScope::Category)
            parameters["purposeId"] != null -> parseCanonicalUuid(parameters["purposeId"])?.let(ReadScope::Purpose)
            parameters["purposeUnassigned"] == "true" -> ReadScope.PurposeUnassigned
            else -> null
        } ?: return ReadQueryParseResult.InvalidQuery
        return window(owner, parameters, scope, ReadEndpoint.WISHLIST_ITEMS, 40)
    }

    fun action(owner: UUID, parameters: Parameters): ReadQueryParseResult {
        if (!validKeys(parameters, windowKeys + "group")) return ReadQueryParseResult.InvalidQuery
        val group = HomeActionGroup.entries.firstOrNull { it.name == parameters["group"] } ?: return ReadQueryParseResult.InvalidQuery
        return window(owner, parameters, ReadScope.Action(group), ReadEndpoint.HOME_ACTION_ITEMS, 20)
    }

    private fun validKeys(p: Parameters, allowed: Set<String>): Boolean = p.names().all {
        it in allowed && p.getAll(it)?.size == 1 && !p[it].isNullOrEmpty()
    }

    private fun window(owner: UUID, p: Parameters, scope: ReadScope, endpoint: ReadEndpoint, defaultLimit: Int): ReadQueryParseResult {
        if (p["anchor"] != null) {
            if (p["cursor"] != null || p["limit"] != null) return ReadQueryParseResult.InvalidQuery
            val before = number(p, "before", 20, 0..20) ?: return ReadQueryParseResult.InvalidQuery
            val after = number(p, "after", 20, 0..20) ?: return ReadQueryParseResult.InvalidQuery
            val position = WishlistReadCursorCodec.decode(owner, endpoint, scope, ReadCursorUse.ANCHOR, p["anchor"]!!)
                ?: return ReadQueryParseResult.InvalidCursor
            return ReadQueryParseResult.Valid(ReadQuery(scope, ReadWindow.Anchor(position, before, after)))
        }
        if (p["before"] != null || p["after"] != null) return ReadQueryParseResult.InvalidQuery
        val limit = number(p, "limit", defaultLimit, 1..100) ?: return ReadQueryParseResult.InvalidQuery
        val cursor = p["cursor"] ?: return ReadQueryParseResult.Valid(ReadQuery(scope, ReadWindow.Page(limit)))
        for ((use, direction) in listOf(ReadCursorUse.NEXT to ReadDirection.OLDER, ReadCursorUse.PREVIOUS to ReadDirection.NEWER)) {
            val position = WishlistReadCursorCodec.decode(owner, endpoint, scope, use, cursor) ?: continue
            return ReadQueryParseResult.Valid(ReadQuery(scope, ReadWindow.Page(limit, position, direction)))
        }
        return ReadQueryParseResult.InvalidCursor
    }
    private fun number(p: Parameters, key: String, default: Int, range: IntRange): Int? =
        if (p[key] == null) default else p[key]?.toIntOrNull()?.takeIf { it in range }
}
