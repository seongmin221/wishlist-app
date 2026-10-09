package app.wishlist

import java.util.UUID
import app.category.CategoryRef

/** Bound values accompany SQL in placeholder order. Identifiers are internal fixed expressions only. */
data class SqlPredicate(val sql: String, val parameters: List<Any?>)

object WishlistReadPredicates {
    const val POLICY_WHITESPACE = "\u0009\u000a\u000b\u000c\u000d\u001c\u001d\u001e\u001f\u0020\u00a0\u1680" +
        "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000"

    fun blank(column: String) = SqlPredicate("($column is null or btrim($column, ?) = '')", listOf(POLICY_WHITESPACE))

    fun categoryVisible(alias: String = "i"): SqlPredicate {
        val name = blank("$alias.product_name")
        return SqlPredicate("($alias.lifecycle_status = 'ACTIVE' and not ${name.sql})", name.parameters)
    }

    fun requiredAction(alias: String = "i"): SqlPredicate = classification(alias) { "'${it.name}'" }

    /** Build action and group CASE expressions from the same ordered conditions. */
    private fun classification(alias: String, value: (RequiredAction) -> String): SqlPredicate {
        val name=blank("$alias.product_name")
        val category=blank("coalesce($alias.category_id,$alias.custom_category_id::text)")
        val conditions=listOf(
            "$alias.lifecycle_status <> 'ACTIVE'" to RequiredAction.NONE,
            "$alias.analysis_status = 'PROCESSING'" to RequiredAction.ANALYSIS_IN_PROGRESS,
            name.sql to RequiredAction.INFORMATION_COMPLETION,
            "$alias.category_missing_reason = 'EXTRACTION_UNRESOLVED'" to RequiredAction.INFORMATION_COMPLETION,
            "$alias.category_missing_reason in ('AI_ABSTAINED','AI_RESPONSE_UNUSABLE')" to RequiredAction.CATEGORY_ASSIGNMENT,
            "$alias.category_missing_reason = 'CUSTOM_CATEGORY_DELETED'" to RequiredAction.CATEGORY_REASSIGNMENT,
            category.sql to RequiredAction.INFORMATION_COMPLETION,
            "$alias.review_status = 'PENDING'" to RequiredAction.CLASSIFICATION_REVIEW)
        return SqlPredicate("case\n"+conditions.joinToString("\n") { (condition,action) ->
            "when $condition then ${value(action)}"
        }+"\nelse ${value(RequiredAction.NONE)} end",name.parameters+category.parameters)
    }

    /** Classify ACTIVE rows once; avoid a second full-owner action/group materialization. */
    fun classified(owner: UUID): SqlPredicate {
        val group=classification("w") { action -> WishlistItemPolicy.homeGroupFor(action)?.let { "'${it.name}'" } ?: "null" }
        return SqlPredicate("""classified as materialized (
            select w.id,w.created_at,${group.sql} grp
            from wishlist_items w where w.owner_id=? and w.lifecycle_status='ACTIVE'
        )""".trimIndent(),group.parameters+owner)
    }

    fun scope(owner: UUID, scope: ReadScope): SqlPredicate = when(scope) {
        is ReadScope.Category -> {
            val visible=categoryVisible()
            val column=if(scope.ref is CategoryRef.Public) "category_id" else "custom_category_id"
            val value=when(val ref=scope.ref) { is CategoryRef.Public -> ref.value; is CategoryRef.Custom -> ref.id }
            SqlPredicate("i.owner_id=? and ${visible.sql} and i.$column=?",listOf(owner)+visible.parameters+value)
        }
        is ReadScope.Purpose -> SqlPredicate("i.owner_id=? and i.lifecycle_status='ACTIVE' and i.purpose_id=?",listOf(owner,scope.id))
        ReadScope.PurposeUnassigned -> SqlPredicate("i.owner_id=? and i.lifecycle_status='ACTIVE' and i.purpose_id is null",listOf(owner))
        is ReadScope.Action -> error("Action scopes use the shared classified CTE")
    }

    fun homeGroup(actionColumn: String): String = "case\n"+HomeActionGroup.entries.joinToString("\n") { group ->
        val actions=RequiredAction.entries.filter { WishlistItemPolicy.homeGroupFor(it)==group }
            .joinToString(",") { "'${it.name}'" }
        "when $actionColumn in ($actions) then '${group.name}'"
    }+"\nelse null end"
}
