package app.category

import app.testutil.*
import app.wishlist.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CategoryVisibilityCountTest {
    @Test fun display_counts_match_public_and_custom_item_windows() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        val custom=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("custom",null,emptyList())).category
        val empty=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("empty",null,emptyList())).category
        val time=Instant.parse("2026-10-07T10:00:00Z")
        fun fixture(name:String?,category:String,customId:UUID?=null)=ReadFixture(ReadPosition(time,UUID.randomUUID()),
            WishlistItemState(AnalysisStatus.PROCESSING,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,name,category,null,null),customId)
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(fixture(null,"C026"),fixture(" \t\n\u00a0","C026"),fixture("retained",custom.id.toString(),custom.id))) }
        assertEquals(0L,service.list(owner,CategoryScope.SELECT,"G003").groups.single().categories.single { it.id=="C026" }.itemCount)
        assertFalse(service.list(owner,CategoryScope.BROWSE,"G003").groups.single().categories.any { it.id=="C026" })
        assertEquals(0L,service.get(owner,empty.id)!!.itemCount)
        assertEquals(1L,service.get(owner,custom.id)!!.itemCount)
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(fixture("retained public","C026"))) }
        val browse=service.list(owner,CategoryScope.BROWSE,"G003").groups.single()
        assertEquals(2L,browse.itemCount)
        assertTrue(browse.categories.any { it.id==empty.id.toString() && it.itemCount==0L })
        for (entry in browse.categories) {
            val scope=ReadScope.Category(CategoryRef.parse(entry.id)!!)
            val page=assertIs<ReadResult.Success>(WishlistReadService(source).read(owner,ReadQuery(scope,ReadWindow.Page(100)))).page
            assertEquals(entry.itemCount,page.totalCount)
        }
    }
    @Test fun display_zero_does_not_mean_no_deletion_impact() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val service=CategoryService(source)
        val custom=service.create(owner,UUID.randomUUID(),"G003",CategoryInput("custom",null,emptyList())).category
        val time=Instant.parse("2026-10-07T10:00:00Z")
        val fixtures=listOf(null,"\u00a0","name").map { name -> ReadFixture(ReadPosition(time,UUID.randomUUID()),
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.CONFIRMED,LifecycleStatus.ACTIVE,name,custom.id.toString(),null,null),custom.id) }
        val inactive=listOf(LifecycleStatus.ARCHIVED,LifecycleStatus.DELETED).map { l -> fixtures.last().copy(position=ReadPosition(time,UUID.randomUUID()),state=fixtures.last().state.copy(lifecycleStatus=l)) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures+inactive) }
        assertEquals(1L,service.get(owner,custom.id)!!.itemCount)
        source.connection.use { c -> c.prepareStatement("select count(*) from wishlist_items where owner_id=? and custom_category_id=? and lifecycle_status='ACTIVE'").use { s ->
            s.setObject(1,owner);s.setObject(2,custom.id);s.executeQuery().use { r -> assertTrue(r.next());assertEquals(3L,r.getLong(1)) }
        } }
        val page=assertIs<ReadResult.Success>(WishlistReadService(source).read(owner,ReadQuery(ReadScope.Category(CategoryRef.Custom(custom.id)),ReadWindow.Page(100)))).page
        assertEquals(1L,page.totalCount);assertNull(service.get(UUID.randomUUID(),custom.id))
    }
}
