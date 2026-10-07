package app.wishlist

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

    fun requiredAction(alias: String = "i"): SqlPredicate {
        val name = blank("$alias.product_name")
        val category = blank("coalesce($alias.category_id,$alias.custom_category_id::text)")
        return SqlPredicate("""case
            when $alias.lifecycle_status <> 'ACTIVE' then 'NONE'
            when $alias.analysis_status = 'PROCESSING' then 'ANALYSIS_IN_PROGRESS'
            when ${name.sql} then 'INFORMATION_COMPLETION'
            when $alias.category_missing_reason = 'EXTRACTION_UNRESOLVED' then 'INFORMATION_COMPLETION'
            when $alias.category_missing_reason in ('AI_ABSTAINED','AI_RESPONSE_UNUSABLE') then 'CATEGORY_ASSIGNMENT'
            when $alias.category_missing_reason = 'CUSTOM_CATEGORY_DELETED' then 'CATEGORY_REASSIGNMENT'
            when ${category.sql} then 'INFORMATION_COMPLETION'
            when $alias.review_status = 'PENDING' then 'CLASSIFICATION_REVIEW'
            else 'NONE' end""".trimIndent(), name.parameters + category.parameters)
    }

    fun homeGroup(actionColumn: String): String = """case
        when $actionColumn = 'ANALYSIS_IN_PROGRESS' then 'ANALYSIS_IN_PROGRESS'
        when $actionColumn in ('INFORMATION_COMPLETION','CATEGORY_ASSIGNMENT','CATEGORY_REASSIGNMENT') then 'INFORMATION_COMPLETION'
        when $actionColumn = 'CLASSIFICATION_REVIEW' then 'CLASSIFICATION_REVIEW'
        else null end""".trimIndent()
}
