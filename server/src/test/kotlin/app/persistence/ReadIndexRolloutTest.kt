package app.persistence

import java.lang.reflect.Proxy
import java.sql.*
import kotlin.test.*

class ReadIndexRolloutTest {
    private data class State(val valid: Boolean, val expectedTable: Boolean = true, val definition: String, val predicate: String = "lifecycle_status = 'ACTIVE'")
    @Test fun rerun_keeps_completed_indexes_and_rebuilds_invalid_index_after_partial_failure() {
        val state=mutableMapOf<String,State>();val executed=mutableListOf<String>();var failThird=true
        val connection=connection(state,executed) { name ->
            if(name=="wishlist_active_owner_order" && failThird) {
                failThird=false;state[name]=State(false,definition="invalid");throw SQLException("interrupted concurrent build")
            }
        }
        assertFailsWith<SQLException> { ReadIndexRollout.apply(connection) }
        assertEquals(2,state.values.count { it.valid })
        ReadIndexRollout.apply(connection)
        assertEquals(3,state.values.count { it.valid })
        assertEquals(1,executed.count { it.startsWith("create index concurrently if not exists") && it.contains("wishlist_active_public_category_order") })
        assertEquals(1,executed.count { it.startsWith("create index concurrently if not exists") && it.contains("wishlist_active_custom_category_order") })
        assertTrue(executed.any { it.startsWith("drop index concurrently if exists") && it.contains("wishlist_active_owner_order") })
    }
    @Test fun rollout_refuses_to_drop_an_index_on_a_different_table() {
        val states=mutableMapOf("wishlist_active_public_category_order" to State(false,false,"foreign"))
        val executed=mutableListOf<String>()
        assertFailsWith<IllegalStateException> { ReadIndexRollout.apply(connection(states,executed) {}) }
        assertTrue(executed.isEmpty())
    }
    @Test fun valid_varchar_predicate_with_postgres_casts_is_kept() {
        val states=mutableMapOf("wishlist_active_public_category_order" to State(true,definition=
            "CREATE INDEX wishlist_active_public_category_order ON public.wishlist_items USING btree (owner_id, category_id, created_at DESC, id DESC) WHERE ((lifecycle_status)::text = 'ACTIVE'::text)",
            predicate="((lifecycle_status)::text = 'ACTIVE'::text)"))
        val executed=mutableListOf<String>()
        ReadIndexRollout.apply(connection(states,executed) {})
        assertTrue(executed.none { it.contains("wishlist_active_public_category_order") })
    }
    @Test fun a_valid_but_different_active_predicate_is_replaced_on_the_expected_table() {
        for(predicate in listOf("lifecycle_status = 'active'","lifecycle_status = 'ACTIVE::text'","lifecycle_status = 'ACTIVE' OR true")) {
            val states=mutableMapOf("wishlist_active_public_category_order" to State(true,definition=
                "CREATE INDEX wishlist_active_public_category_order ON public.wishlist_items USING btree (owner_id, category_id, created_at DESC, id DESC) WHERE $predicate",predicate=predicate))
            val executed=mutableListOf<String>()
            ReadIndexRollout.apply(connection(states,executed) {})
            assertTrue(executed.any { it.startsWith("drop index concurrently") && it.contains("wishlist_active_public_category_order") })
            assertEquals("lifecycle_status = 'ACTIVE'",states.getValue("wishlist_active_public_category_order").predicate)
        }
    }
    private fun connection(states: MutableMap<String,State>, executed: MutableList<String>, beforeCreate: (String)->Unit): Connection =
        proxy(Connection::class.java) { method,_ -> when(method) {
            "getAutoCommit" -> true
            "prepareStatement" -> {
                var name=""
                proxy(PreparedStatement::class.java) { m,args -> when(m) {
                    "setString" -> { name=args!![1] as String;null }
                    "close" -> null
                    "executeQuery" -> result(states[name])
                    else -> error(m)
                } }
            }
            "createStatement" -> proxy(Statement::class.java) { m,args -> when(m) {
                "close" -> null
                "execute" -> {
                    val sql=args!![0] as String;executed.add(sql)
                    val name=Regex("wishlist_active_[a-z_]+").find(sql)!!.value
                    if(sql.startsWith("drop")) states.remove(name)
                    else {
                        beforeCreate(name)
                        val columns=sql.substringAfter("on wishlist_items")
                        states[name]=State(true,definition="CREATE INDEX $name ON public.wishlist_items USING btree $columns")
                    }
                    false
                }
                else -> error(m)
            } }
            else -> error(method)
        } }
    private fun result(state: State?): ResultSet {
        var read=false
        return proxy(ResultSet::class.java) { method,args -> when(method) {
            "next" -> (!read && state!=null).also { read=true }
            "close" -> null
            "getBoolean" -> if(args!![0]=="expected_table") state!!.expectedTable else state!!.valid
            "getString" -> if(args!![0]=="predicate") state!!.predicate else state!!.definition
            else -> error(method)
        } }
    }
    private fun <T> proxy(type: Class<T>, body: (String,Array<out Any>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args -> body(method.name,args) })
}
