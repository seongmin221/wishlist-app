package app.wishlist

import app.DatabaseFactory
import app.category.*
import app.home.HomeReadRepository
import app.persistence.inTransaction
import app.purpose.PurposeRepository
import app.testutil.*
import org.flywaydb.core.Flyway
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*
import kotlin.test.*

class WishlistReadIndexTest {
    @Test fun read_indexes_cover_order_and_preserve_results() = PostgresTestContainer().use { db ->
        db.start()
        Flyway.configure().dataSource(db.jdbcUrl, db.username, db.password).target("14").load().migrate()
        val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
        var custom: UUID? = null
        var purpose: UUID? = null
        val time = Instant.parse("2026-10-07T10:00:00Z")
        for (o in 1..2) {
            val id = UUID(0, o.toLong())
            val p = insertPurpose(source,id,"purpose",null)
            val cat = CategoryService(source).create(id,UUID.randomUUID(),"G003",CategoryInput("custom",null,emptyList())).category.id
            if (o == 1) { custom = cat; purpose = p }
            val fixtures = (0 until 10_000).map { n ->
                val special = n % 100
                val isCustom = n % 2 == 1
                val category = if (isCustom) cat.toString() else "C026"
                val state = WishlistItemState(
                    if(special == 0) AnalysisStatus.PROCESSING else AnalysisStatus.READY,
                    if(special == 2) ReviewStatus.PENDING else ReviewStatus.CONFIRMED,
                    LifecycleStatus.ACTIVE, if(special == 1) null else "item $n", category, null, null)
                val pId = if(n % 4 < 2) p else null
                ReadFixture(ReadPosition(time.minusSeconds((n / 20).toLong()), UUID(o.toLong(),n.toLong() + 1)),state,
                    if(isCustom) cat else null,pId,if(pId == null) ValueSource.UNASSIGNED else ValueSource.USER)
            }
            assertEquals(mapOf(null to 9700, HomeActionGroup.ANALYSIS_IN_PROGRESS to 100,
                HomeActionGroup.INFORMATION_COMPLETION to 100,HomeActionGroup.CLASSIFICATION_REVIEW to 100),
                fixtures.groupingBy { WishlistItemPolicy.evaluate(it.state).homeActionGroup }.eachCount())
            source.connection.use { c -> insertReadFixtures(c,id,fixtures) }
        }
        source.connection.use { c -> c.createStatement().use { it.execute("analyze wishlist_items") } }
        val scopes = linkedMapOf("public" to ReadScope.Category(CategoryRef.Public("C026")),
            "custom" to ReadScope.Category(CategoryRef.Custom(custom!!)), "purpose" to ReadScope.Purpose(purpose!!),
            "unassigned" to ReadScope.PurposeUnassigned)
        val repo = WishlistReadRepository()
        val recording = ReadRecordingDataSource(source)
        val captured = linkedMapOf<String,RecordedReadQuery>()
        fun capture(name: String, operation: (java.sql.Connection) -> Unit) {
            recording.inTransaction(readOnly = true) { c -> operation(c) }
            captured[name] = recording.queries.last()
        }
        for ((name,scope) in scopes) {
            capture("$name-page") { c -> repo.keys(c,owner,scope,null,ReadDirection.OLDER,41) }
            val boundary = source.inTransaction(readOnly=true) { c -> repo.keys(c,owner,scope,null,ReadDirection.OLDER,101).last() }
            capture("$name-anchor-older") { c -> repo.keys(c,owner,scope,boundary,ReadDirection.OLDER,21) }
            capture("$name-anchor-newer") { c -> repo.keys(c,owner,scope,boundary,ReadDirection.NEWER,21) }
        }
        for (g in HomeActionGroup.entries) {
            val scope = ReadScope.Action(g)
            capture("home-${g.name}-page") { c -> repo.keys(c,owner,scope,null,ReadDirection.OLDER,21) }
            capture("home-${g.name}-count") { c -> repo.count(c,owner,scope) }
        }
        capture("home-summary") { c -> HomeReadRepository().groupSummaries(c,owner) }
        val start = recording.queries.size
        CategoryService(recording).list(owner,CategoryScope.BROWSE,null)
        val categoryQueries = recording.queries.drop(start).filter { it.sql.contains("wishlist_items") }
        assertEquals(2,categoryQueries.size)
        captured["cat-custom-count"] = categoryQueries[0]
        captured["cat-public-count"] = categoryQueries[1]
        capture("purpose-summary") { c -> PurposeRepository().page(c,owner,null,3) }
        val before = measure(source,captured,"V14")
        DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
        val after = measure(source,captured,"V15")
        println("EXPLAIN comparison: same fixture, same bound queries; rows=20000, per-owner=10000, groups=100/100/100, NONE=9700")
        for (name in captured.keys) println("$name: ${before.getValue(name)} => ${after.getValue(name)}")
        source.connection.use { c ->
            for ((index,column) in listOf("wishlist_active_public_category_order" to "category_id",
                "wishlist_active_custom_category_order" to "custom_category_id", "wishlist_active_owner_order" to null)) {
                val definition = c.prepareStatement("select indexdef from pg_indexes where indexname=?").use { s ->
                    s.setString(1,index);s.executeQuery().use { r -> if(r.next()) r.getString(1) else null }
                }
                assertNotNull(definition, "missing $index")
                val cols = listOfNotNull("owner_id",column,"created_at DESC","id DESC").joinToString(", ")
                assertTrue(definition.contains("($cols)"),definition)
                assertTrue(definition.contains("lifecycle_status") && definition.contains("'ACTIVE'"),definition)
            }
            c.createStatement().use { s -> s.executeQuery("select count(*) from pg_indexes where indexname in ('wishlist_active_public_category','wishlist_active_custom_category')").use { r -> r.next();assertEquals(0,r.getInt(1)) } }
        }
        val service = WishlistReadService(source)
        for (g in HomeActionGroup.entries) {
            val page = assertIs<ReadResult.Success>(service.read(owner,ReadQuery(ReadScope.Action(g),ReadWindow.Page(20)))).page
            assertEquals(100L,page.totalCount);assertEquals(20,page.items.size)
            assertTrue(page.items.all { WishlistItemPolicy.evaluate(it.storedState.state).homeActionGroup == g })
            assertEquals(page.items.map { it.id }, source.inTransaction(readOnly=true) { c -> repo.keys(c,owner,ReadScope.Action(g),null,ReadDirection.OLDER,20).map { it.id } })
        }
        for ((_,scope) in scopes) {
            val page = assertIs<ReadResult.Success>(service.read(owner,ReadQuery(scope,ReadWindow.Page(40)))).page
            assertEquals(40,page.items.size)
            assertEquals(if(scope is ReadScope.Category && scope.ref is CategoryRef.Custom) 4900L else 5000L,page.totalCount)
        }
    }

    private fun measure(source: DataSource, queries: Map<String,RecordedReadQuery>, version: String): Map<String,String> =
        source.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("select version()").use { r -> r.next();println("$version: ${r.getString(1)}") } }
            queries.mapValues { (_,q) ->
                val explain = c.prepareStatement("explain (analyze, buffers, format json) ${q.sql}").use { s ->
                    q.parameters.forEachIndexed { n,v -> s.setObject(n+1,v) }
                    s.executeQuery().use { r -> r.next();Json.parseToJsonElement(r.getString(1)).jsonArray.single().jsonObject }
                }
                val nodes = mutableListOf<JsonObject>()
                fun visit(node: JsonObject) { nodes.add(node);node["Plans"]?.jsonArray?.forEach { visit(it.jsonObject) } }
                val root = explain.getValue("Plan").jsonObject;visit(root)
                fun metric(key: String) = root[key]?.jsonPrimitive?.content ?: "0"
                val indexes = nodes.mapNotNull { it["Index Name"]?.jsonPrimitive?.content }.distinct()
                val scanned = nodes.filter { it["Relation Name"]?.jsonPrimitive?.content == "wishlist_items" }.sumOf {
                    (it.getValue("Actual Rows").jsonPrimitive.long + (it["Rows Removed by Filter"]?.jsonPrimitive?.long ?: 0)) * it.getValue("Actual Loops").jsonPrimitive.long }
                "ms=${explain.getValue("Execution Time")}, scan=$scanned, shared=${metric("Shared Hit Blocks")}/${metric("Shared Read Blocks")}, temp=${metric("Temp Read Blocks")}/${metric("Temp Written Blocks")}, sort=${nodes.mapNotNull { it["Sort Method"]?.jsonPrimitive?.content }.distinct()}, indexes=$indexes"
            }
        }
}
