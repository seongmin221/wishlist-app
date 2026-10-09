package app.category

import app.DatabaseFactory
import app.ai.TaxonomyCatalog
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.sql.SQLException
import kotlin.test.*

class CategorySchemaTest {
    @Test fun `V12 keeps unknown historical public reference but rejects new references`() = PostgresTestContainer().use { db ->
        db.start()
        DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).target("11").load().migrate()
        val source=DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
        val owner=UUID.randomUUID()
        // Seed the V11 row with SQL: current item services target the latest schema.
        val item=UUID.randomUUID()
        analysisSql(source,"""insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status,category_id,category_source,category_missing_reason)
            values ('$item','$owner','${UUID.randomUUID()}','https://example.com/item','READY','ACTIVE','LEGACY_UNKNOWN','USER',null)""")
        DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
        assertEquals("LEGACY_UNKNOWN",analysisScalar(source,"select category_id from wishlist_items where id='$item'"))
        analysisSql(source,"update wishlist_items set product_name='preserved' where id='$item'")
        assertEquals("23503",assertFailsWith<SQLException> { analysisSql(source,"update wishlist_items set category_id='NEW_UNKNOWN' where id='$item'") }.sqlState)
    }

    @Test fun `new public references require taxonomy IDs`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID()
        val item=CreateWishlistItemService(source).create(owner,UUID.randomUUID(),"https://example.com/item").createdItemId
        val failure=assertFailsWith<java.sql.SQLException> { analysisSql(source,"update wishlist_items set category_id='UNKNOWN_PUBLIC',category_source='AI',category_missing_reason=null where id='$item'") }
        assertEquals("23503",failure.sqlState)
    }

    @Test fun `V10 upgrade preserves sealed public snapshot and backfills owner`() = PostgresTestContainer().use { db ->
        db.start()
        DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).target("10").load().migrate()
        val source=DatabaseFactory.dataSource(db.jdbcUrl,db.username,db.password)
        val owner=UUID.randomUUID(); val item=UUID.randomUUID(); val job=UUID.randomUUID()
        val snapshot="""{"categories":["C026"],"purposes":[],"category_labels":{"C026":"헤드폰"}}"""
        analysisSql(source,"insert into wishlist_items(id,owner_id,client_submission_id,source_url,analysis_status,lifecycle_status) values ('$item','$owner','${UUID.randomUUID()}','https://example.com','PROCESSING','ACTIVE')")
        source.connection.use { c -> c.prepareStatement("insert into analysis_jobs(id,wishlist_item_id,generation,stage,candidate_snapshot_json) values (?,?,1,'GENERAL_PENDING',?)").use { s ->
            s.setObject(1,job);s.setObject(2,item);s.setString(3,snapshot);s.executeUpdate()
        } }
        DatabaseFactory.migrate(db.jdbcUrl,db.username,db.password)
        assertEquals(snapshot,analysisScalar(source,"select candidate_snapshot_json from analysis_jobs where id='$job'"))
        assertEquals(owner.toString(),analysisScalar(source,"select id from app_users where id='$owner'"))
        assertNull(analysisScalar(source,"select custom_category_id from wishlist_items where id='$item'"))
        DatabaseFactory.migrationConfiguration(db.jdbcUrl,db.username,db.password).load().validate()
    }

    @Test fun `all seeded public ids parents labels and order match versioned resource`() = withAnalysisDatabase { source ->
        val registry=PublicCategoryRegistry(TaxonomyCatalog.loadV1())
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("select id,name from public_category_groups order by display_order").use { r ->
                val actual=buildList { while(r.next()) add(r.getString(1) to r.getString(2)) }
                assertEquals(listOf("G001" to "패션·잡화","G002" to "뷰티·퍼스널케어","G003" to "디지털·IT","G004" to "가구·인테리어",
                    "G005" to "생활·주방·가전","G006" to "스포츠·아웃도어·여행","G007" to "취미·문화·컬렉터블","G008" to "유아·키즈",
                    "G009" to "반려동물","G010" to "자동차·모빌리티","G011" to "건강·웰빙"),actual)
            }
            s.executeQuery("select id,parent_id,name,display_order from public_categories order by id").use { r ->
                val actual=buildList { while(r.next()) add(listOf(r.getString(1),r.getString(2),r.getString(3),r.getInt(4).toString())) }
                assertEquals(registry.groups.flatMap { it.categories }.map { listOf(it.id,it.parentId,it.name,it.displayOrder.toString()) },actual)
            }
        } }
    }

    @Test fun `custom assignment constraints reject missing source reason dual reference and parent movement`() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID()
        val category=CategoryService(source).create(owner,UUID.randomUUID(),"G003",CategoryInput("desk",null,emptyList())).category
        val item=CreateWishlistItemService(source).create(owner,UUID.randomUUID(),"https://example.com").createdItemId
        for(assignment in listOf(
            "custom_category_id='${category.id}',category_source=null,category_missing_reason=null",
            "custom_category_id='${category.id}',category_source='UNASSIGNED',category_missing_reason=null",
            "custom_category_id='${category.id}',category_source='AI',category_missing_reason='AI_ABSTAINED'",
            "custom_category_id='${category.id}',category_id='C026',category_source='USER',category_missing_reason=null"
        )) assertEquals("23514",assertFailsWith<SQLException> { analysisSql(source,"update wishlist_items set $assignment where id='$item'") }.sqlState)
        assertEquals("23514",assertFailsWith<SQLException> { analysisSql(source,"update custom_categories set parent_id='G004' where id='${category.id}'") }.sqlState)
        assertEquals("23514",assertFailsWith<SQLException> { analysisSql(source,"update custom_categories set examples=array['${"a".repeat(61)}'] where id='${category.id}'") }.sqlState)
        analysisSql(source,"update wishlist_items set custom_category_id='${category.id}',category_source='USER',category_missing_reason=null where id='$item'")
        assertEquals("1",analysisScalar(source,"select count(*) from wishlist_items where custom_category_id='${category.id}'"))
    }
}
