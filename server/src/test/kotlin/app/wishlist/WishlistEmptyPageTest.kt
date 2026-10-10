package app.wishlist

import app.category.CategoryRef
import app.http.*
import io.ktor.http.Parameters
import java.lang.reflect.Proxy
import java.sql.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

/** Component tests for navigation/query control. Actual SQL remains covered by PostgreSQL tests. */
class WishlistEmptyPageTest {
    private val owner = UUID.randomUUID()
    private val position = ReadPosition(Instant.parse("2026-10-07T10:00:00Z"),UUID.randomUUID())
    private val scope = ReadScope.Category(CategoryRef.Public("C026"))

    @Test fun empty_older_page_retains_return_cursor() = emptyPage(ReadDirection.OLDER)
    @Test fun empty_newer_page_retains_return_cursor() = emptyPage(ReadDirection.NEWER)

    private fun emptyPage(direction: ReadDirection) {
        val source = source(direction,mutableListOf())
        val page = assertIs<ReadResult.Success>(WishlistReadService(source).read(owner,ReadQuery(scope,ReadWindow.Page(2,position,direction)))).page
        assertTrue(page.items.isEmpty());assertEquals(1L,page.totalCount)
        val dto = ReadWindowViewMapper.map(owner,ReadEndpoint.WISHLIST_ITEMS,scope,page)
        val cursor = assertNotNull(if(direction==ReadDirection.OLDER) dto.previousCursor else dto.nextCursor)
        val parsed = assertIs<ReadQueryParseResult.Valid>(WishlistReadQueryParser.wishlist(owner,Parameters.build {
            append("categoryId","C026");append("cursor",cursor)
        }))
        val window = assertIs<ReadWindow.Page>(parsed.query.window)
        assertEquals(if(direction==ReadDirection.OLDER) ReadDirection.NEWER else ReadDirection.OLDER,window.direction)
        assertEquals(position,window.boundary)
        assertTrue(window.boundaryInclusive)
    }

    @Test fun first_page_does_not_probe_the_newer_side() {
        val queries=mutableListOf<String>()
        val page=assertIs<ReadResult.Success>(WishlistReadService(source(null,queries)).read(owner,ReadQuery(scope,ReadWindow.Page(2)))).page
        assertEquals(listOf(position.id),page.items.map { it.id })
        assertNull(page.previous)
        assertTrue(queries.none { it.startsWith("select exists(") },queries.toString())
        assertEquals(2,queries.size,"one window query plus one batch projection")
    }

    private fun source(emptyDirection: ReadDirection?, queries: MutableList<String>): DataSource {
        val key=mapOf<Any,Any?>("id" to position.id,"created_at" to Timestamp.from(position.createdAt))
        val item=key+mapOf("owner_id" to owner,"version" to 1,"current_generation" to 1,
            "analysis_status" to "READY","review_status" to "PENDING","lifecycle_status" to "ACTIVE",
            "product_name" to "name","category_id" to "C026","category_source" to "USER",
            "purpose_source" to "UNASSIGNED","client_submission_id" to UUID.randomUUID(),
            "source_url" to "https://example.com/item","updated_at" to Timestamp.from(position.createdAt),
            "user_override_fields" to array(emptyArray<String>()))
        val connection=proxy(Connection::class.java) { method,args -> when(method) {
            "prepareStatement" -> {
                val sql=args!![0] as String
                proxy(PreparedStatement::class.java) { m,_ -> when {
                    m=="executeQuery" -> {
                        queries.add(sql)
                        val rows=when {
                            sql.trimStart().startsWith("with ") -> listOf(mapOf<Any,Any?>(
                                "total_count" to 1L,"has_previous" to false,"has_next" to false,
                                "id" to position.id.takeIf { emptyDirection==null },
                                "created_at" to Timestamp.from(position.createdAt).takeIf { emptyDirection==null },
                                "recovery_id" to position.id.takeIf { emptyDirection!=null },
                                "recovery_at" to Timestamp.from(position.createdAt).takeIf { emptyDirection!=null },
                                "first_id" to position.id.takeIf { emptyDirection==null },"first_at" to Timestamp.from(position.createdAt),
                                "last_id" to position.id.takeIf { emptyDirection==null },"last_at" to Timestamp.from(position.createdAt)))
                            sql.startsWith("select count(*)") -> listOf(mapOf<Any,Any?>(1 to 1L))
                            sql.startsWith("select exists(") -> listOf(mapOf<Any,Any?>(1 to false))
                            sql.contains("i.id=any(?)") -> listOf(item)
                            emptyDirection==ReadDirection.OLDER && sql.contains("< (?,?)") -> emptyList()
                            emptyDirection==ReadDirection.NEWER && sql.contains("> (?,?)") -> emptyList()
                            else -> listOf(key)
                        }
                        result(rows)
                    }
                    m.startsWith("set") || m=="close" -> null
                    else -> error(m)
                } }
            }
            "createArrayOf" -> array(args!![1] as Array<*>)
            "setTransactionIsolation","setReadOnly","setAutoCommit","commit","rollback","close" -> null
            else -> error(method)
        } }
        return proxy(DataSource::class.java) { method,_ -> if(method=="getConnection") connection else error(method) }
    }
    private fun result(rows: List<Map<Any,Any?>>): ResultSet {
        var index=-1
        return proxy(ResultSet::class.java) { method,args -> when(method) {
            "next" -> ++index<rows.size
            "close" -> null
            "getInt" -> (rows[index][args!![0]] as? Number)?.toInt() ?: 0
            "getLong" -> (rows[index][args!![0]] as Number).toLong()
            "getBoolean" -> rows[index][args!![0]] as Boolean
            "getString","getObject","getTimestamp","getArray","getBigDecimal" -> rows[index][args!![0]]
            else -> error(method)
        } }
    }
    private fun array(values: Array<*>): java.sql.Array = proxy(java.sql.Array::class.java) { method,_ -> when(method) {
        "getArray" -> values
        "free" -> null
        else -> error(method)
    } }
    private fun <T> proxy(type: Class<T>, body: (String,Array<out Any>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,m,args -> body(m.name,args) })
}
