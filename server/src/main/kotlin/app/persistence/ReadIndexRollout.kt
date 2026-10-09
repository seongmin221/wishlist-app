package app.persistence

import java.sql.Connection

/** Part of immutable V16: inspect catalog state before guarded, non-transactional DDL. */
internal object ReadIndexRollout {
    private data class Definition(val name: String, val columns: String)
    private data class State(val valid: Boolean, val expectedTable: Boolean, val definition: String, val predicate: String?)
    private val definitions=listOf(
        Definition("wishlist_active_public_category_order","owner_id, category_id, created_at DESC, id DESC"),
        Definition("wishlist_active_custom_category_order","owner_id, custom_category_id, created_at DESC, id DESC"),
        Definition("wishlist_active_owner_order","owner_id, created_at DESC, id DESC"))

    fun apply(connection: Connection) {
        check(connection.autoCommit) { "Concurrent index rollout must run outside a transaction" }
        for(index in definitions) {
            var state=state(connection,index.name)
            if(state!=null) {
                check(state.expectedTable) { "${index.name} belongs to a different table; refusing to change it" }
                if(!matches(state,index)) {
                    execute(connection,"drop index concurrently if exists ${index.name}")
                    state=null
                }
            }
            if(state==null) execute(connection,"create index concurrently if not exists ${index.name} on wishlist_items (${index.columns}) where lifecycle_status = 'ACTIVE'")
            val completed=checkNotNull(state(connection,index.name)) { "Missing ${index.name} after rollout" }
            check(matches(completed,index)) {
                "${index.name} is invalid or has an unexpected definition after rollout"
            }
        }
        for(name in listOf("wishlist_active_public_category","wishlist_active_custom_category")) {
            val legacy=state(connection,name)
            check(legacy==null || legacy.expectedTable) { "$name belongs to a different table; refusing to remove it" }
            execute(connection,"drop index concurrently if exists $name")
        }
    }
    private fun matches(state: State, index: Definition): Boolean {
        val definition=state.definition.lowercase().replace(Regex("\\s+")," ")
        // PostgreSQL prints varchar comparison casts and redundant parentheses; literal case matters.
        val activePredicate=state.predicate?.let {
            Regex("""^\(*\s*\(*\s*lifecycle_status\s*\)*(?:\s*::\s*text)?\s*=\s*'ACTIVE'(?:\s*::\s*text)?\s*\)*$""").matches(it.trim())
        } == true
        return state.valid && state.expectedTable && definition.startsWith("create index ") && definition.contains("using btree (${index.columns.lowercase()})") && activePredicate
    }
    private fun state(connection: Connection, name: String): State? = connection.prepareStatement("""
        select i.indisvalid and i.indisready and i.indislive valid,
               i.indrelid='wishlist_items'::regclass expected_table,
               pg_get_indexdef(i.indexrelid) definition,
               pg_get_expr(i.indpred,i.indrelid) predicate
        from pg_index i join pg_class idx on idx.oid=i.indexrelid
        join pg_namespace ns on ns.oid=idx.relnamespace
        where ns.nspname=current_schema() and idx.relname=?
    """).use { statement ->
        statement.setString(1,name)
        statement.executeQuery().use { rows -> if(rows.next()) State(rows.getBoolean("valid"),rows.getBoolean("expected_table"),rows.getString("definition"),rows.getString("predicate")) else null }
    }
    private fun execute(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }
}
