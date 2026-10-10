package app.wishlist

import app.DatabaseFactory
import app.category.*
import app.home.HomeReadRepository
import app.persistence.inTransaction
import app.purpose.PurposeRepository
import app.testutil.*
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*
import kotlin.test.*

class WishlistReadIndexTest {
    @Test fun read_indexes_cover_order_and_preserve_results() = PostgresTestContainer().use { db ->
        db.start()
        DatabaseFactory.migrationConfiguration(db.jdbcUrl, db.username, db.password).target("14").load().migrate()
        val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
        // The shared projection reads V17 metadata columns. Add them only for the V14 baseline and drop them before
        // migrating; nullable unindexed columns do not change the measured plans.
        val v17Columns = listOf("product_brand text", "product_price numeric(19,4)", "product_currency char(3)", "merchant_name text", "metadata_checked_at timestamptz")
        source.connection.use { c -> c.createStatement().use { s -> v17Columns.forEach { s.execute("alter table wishlist_items add column $it") } } }
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
        val recording = ReadRecordingDataSource(source)
        val captured = linkedMapOf<String,RecordedReadQuery>()
        fun capture(name: String, operation: (java.sql.Connection) -> Unit) {
            recording.inTransaction(readOnly = true) { c -> operation(c) }
            captured[name] = recording.queries.last()
        }
        fun captureWindow(name: String, scope: ReadScope, window: ReadWindow): ReadPage {
            val start=recording.queries.size
            val page=assertIs<ReadResult.Success>(WishlistReadService(recording).read(owner,ReadQuery(scope,window))).page
            val actual=recording.queries.drop(start).filter { it.sql.trimStart().startsWith("with ") }
            assertEquals(1,actual.size,"EXPLAIN must use the window query actually executed by the service")
            captured[name]=actual.single()
            return page
        }
        for ((name,scope) in scopes) {
            val first=captureWindow("$name-page",scope,ReadWindow.Page(40))
            val middle=ReadPosition(first.items.last().createdAt,first.items.last().id)
            captureWindow("$name-next",scope,ReadWindow.Page(40,middle))
            captureWindow("$name-previous",scope,ReadWindow.Page(40,middle,ReadDirection.NEWER))
            captureWindow("$name-anchor",scope,ReadWindow.Anchor(middle,20,20))
        }
        for(g in HomeActionGroup.entries) {
            val scope=ReadScope.Action(g)
            val first=captureWindow("home-${g.name}-page",scope,ReadWindow.Page(20))
            captureWindow("home-${g.name}-anchor",scope,ReadWindow.Anchor(ReadPosition(first.items.first().createdAt,first.items.first().id),20,20))
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
        source.connection.use { c -> c.createStatement().use { s -> v17Columns.forEach { s.execute("alter table wishlist_items drop column ${it.substringBefore(' ')}") } } }
        DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
        val after = measure(source,captured,"V16 (original V15 retained)")
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
            val classified=WishlistReadPredicates.classified(owner)
            val oracle=source.connection.use { c -> c.prepareStatement("with ${classified.sql} select id from classified where grp=? order by created_at desc,id desc limit 20").use { statement ->
                (classified.parameters+g.name).forEachIndexed { n,value -> statement.setObject(n+1,value) }
                statement.executeQuery().use { rows -> buildList { while(rows.next()) add(rows.getObject(1,UUID::class.java)) } }
            } }
            assertEquals(oracle,page.items.map { it.id })
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
                if(q.sql.contains("classified as materialized")) {
                    assertEquals(1,nodes.count { it["Relation Name"]?.jsonPrimitive?.content=="wishlist_items" },
                        "HOME classification must have one base-table scan in the actual EXPLAIN plan")
                }
                val indexes = nodes.mapNotNull { it["Index Name"]?.jsonPrimitive?.content }.distinct()
                val scanned = nodes.filter { it["Relation Name"]?.jsonPrimitive?.content == "wishlist_items" }.sumOf {
                    (it.getValue("Actual Rows").jsonPrimitive.long + (it["Rows Removed by Filter"]?.jsonPrimitive?.long ?: 0)) * it.getValue("Actual Loops").jsonPrimitive.long }
                "ms=${explain.getValue("Execution Time")}, scan=$scanned, shared=${metric("Shared Hit Blocks")}/${metric("Shared Read Blocks")}, temp=${metric("Temp Read Blocks")}/${metric("Temp Written Blocks")}, sort=${nodes.mapNotNull { it["Sort Method"]?.jsonPrimitive?.content }.distinct()}, indexes=$indexes"
            }
        }
}
