package app.testutil

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Collections
import javax.sql.DataSource

data class RecordedReadQuery(val sql: String, val parameters: List<Any?>)

class ReadRecordingDataSource(private val source: DataSource, private val afterSelect: (String) -> Unit = {}) : DataSource by source {
    val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val queries: MutableList<RecordedReadQuery> = Collections.synchronizedList(mutableListOf())
    override fun getConnection(): Connection = wrap(source.connection)
    override fun getConnection(username: String, password: String): Connection = wrap(source.getConnection(username,password))
    private fun wrap(c: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,args ->
        val result=invoke(c,method,args)
        if(method.name=="prepareStatement" && result is PreparedStatement && args?.firstOrNull() is String) {
            val sql=args[0] as String
            val parameters = sortedMapOf<Int, Any?>()
            Proxy.newProxyInstance(PreparedStatement::class.java.classLoader,arrayOf(PreparedStatement::class.java)) { _,m,a ->
                val value=invoke(result,m,a)
                if(m.name.startsWith("set") && a?.firstOrNull() is Int) parameters[a[0] as Int] = if(m.name=="setNull") null else a[1]
                if(m.name=="executeQuery") { statements.add(sql);queries.add(RecordedReadQuery(sql,parameters.values.toList()));afterSelect(sql) }
                value
            }
        } else result
    } as Connection
    private fun invoke(target: Any, method: Method, args: Array<out Any>?): Any? = try { method.invoke(target,*(args ?: emptyArray())) }
        catch(e: InvocationTargetException) { throw e.targetException }
}
