package app.home

import app.wishlist.HomeActionGroup
import java.lang.reflect.Proxy
import java.sql.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

/** Fault injection: a key has no corresponding batch projection row. */
class HomeReadProjectionFallbackTest {
    @Test fun missing_projection_row_fails_instead_of_returning_inconsistent_counts() {
        val id=UUID.randomUUID();val time=Timestamp.from(Instant.parse("2026-10-07T10:00:00Z"))
        val connection=proxy(Connection::class.java) { method,args -> when(method) {
            "prepareStatement" -> {
                val sql=args!![0] as String
                proxy(PreparedStatement::class.java) { m,_ -> when {
                    m=="executeQuery" -> result(if(sql.contains("counts as")) HomeActionGroup.entries.map { group ->
                        mapOf("grp" to group.name,"cnt" to if(group==HomeActionGroup.ANALYSIS_IN_PROGRESS) 1L else 0L,
                            "id" to id.takeIf { group==HomeActionGroup.ANALYSIS_IN_PROGRESS },"created_at" to time)
                    } else emptyList())
                    m.startsWith("set") || m=="close" -> null
                    else -> error(m)
                } }
            }
            "createArrayOf" -> proxy(java.sql.Array::class.java) { m,_ -> if(m=="free") null else error(m) }
            "setTransactionIsolation","setReadOnly","setAutoCommit","commit","rollback","close" -> null
            else -> error(method)
        } }
        val source=proxy(DataSource::class.java) { method,_ -> if(method=="getConnection") connection else error(method) }
        val error=assertFailsWith<IllegalStateException> { HomeReadService(source).get(UUID.randomUUID()) }
        assertTrue(error.message.orEmpty().contains(id.toString()))
    }
    private fun result(rows: List<Map<String,Any?>>): ResultSet {
        var index=-1
        return proxy(ResultSet::class.java) { method,args -> when(method) {
            "next" -> ++index<rows.size
            "close" -> null
            "getString","getObject","getTimestamp","getLong" -> rows[index][args!![0]]
            else -> error(method)
        } }
    }
    private fun <T> proxy(type: Class<T>, body: (String,Array<out Any>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args -> body(method.name,args) })
}
