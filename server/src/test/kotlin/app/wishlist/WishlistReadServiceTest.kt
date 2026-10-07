package app.wishlist

import app.category.CategoryRef
import app.testutil.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class WishlistReadServiceTest {
    @Test fun same_timestamp_pages_roundtrip_in_postgres_uuid_order() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures=(0 until 105).map { n -> ReadFixture(ReadPosition(time,UUID.fromString("${if(n%2==0) "7fffffff" else "80000000"}-0000-4000-8000-${"%012x".format(n)}")),state,clientCreatedAt=time.plusSeconds(n.toLong())) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures) }
        val expected=source.connection.use { c -> c.prepareStatement("select id from wishlist_items where owner_id=? order by created_at desc,id desc").use { s ->
            s.setObject(1,owner);s.executeQuery().use { r -> buildList { while(r.next()) add(r.getObject(1,UUID::class.java)) } }
        } }
        val service=WishlistReadService(source);val scope=ReadScope.Category(CategoryRef.Public("C026"))
        fun page(window: ReadWindow) = assertIs<ReadResult.Success>(service.read(owner,ReadQuery(scope,window))).page
        val first=page(ReadWindow.Page(40));val second=page(ReadWindow.Page(40,first.next));val third=page(ReadWindow.Page(40,second.next))
        assertEquals(expected,(first.items+second.items+third.items).map { it.id })
        assertEquals(105L,first.totalCount);assertNull(first.previous);assertNotNull(first.next)
        assertNotNull(second.previous);assertNotNull(second.next);assertNull(third.next)
        val back=page(ReadWindow.Page(40,second.previous,ReadDirection.NEWER))
        assertEquals(first.items,back.items);assertNull(back.previous);assertNotNull(back.next)
        assertEquals(expected.take(100),page(ReadWindow.Page(100)).items.map { it.id })
    }
    @Test fun anchor_recovers_removed_or_moved_position() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val purpose=insertPurpose(source,owner,"p",null);val otherPurpose=insertPurpose(source,owner,"other",null)
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures=(0 until 50).map { ReadFixture(ReadPosition(time.minusSeconds(it.toLong()),UUID.randomUUID()),state,purposeId=purpose,purposeSource=ValueSource.USER) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures) }
        val service=WishlistReadService(source);val scope=ReadScope.Category(CategoryRef.Public("C026"))
        fun page(at: Int,before: Int=20,after: Int=20,s:ReadScope=scope)=assertIs<ReadResult.Success>(service.read(owner,ReadQuery(s,ReadWindow.Anchor(fixtures[at].position,before,after)))).page
        val normal=page(20)
        assertEquals(true,normal.anchorResolved);assertEquals(fixtures.take(41).map { it.position.id },normal.items.map { it.id })
        val zero=page(20,0,0);assertEquals(listOf(fixtures[20].position.id),zero.items.map { it.id });assertNotNull(zero.previous);assertNotNull(zero.next)
        val extra=ReadFixture(ReadPosition(time.plusSeconds(1),UUID.randomUUID()),state,purposeId=purpose,purposeSource=ValueSource.USER)
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(extra)) }
        assertEquals(normal.items,page(20).items)
        for (at in listOf(0,20,49)) {
            analysisSql(source,"update wishlist_items set lifecycle_status='DELETED' where id='${fixtures[at].position.id}'")
            val result=page(at)
            assertEquals(false,result.anchorResolved);assertEquals(fixtures[at].position.id,result.requestedAnchorItemId)
            val replacement=fixtures[if(at==49)48 else at+1]
            assertEquals(replacement.position.id,result.resolvedAnchorItemId);assertTrue(result.items.size<=41)
            assertFalse(result.items.any { it.id==fixtures[at].position.id })
            if(at==49) assertEquals(21,result.items.size)
            analysisSql(source,"update wishlist_items set lifecycle_status='ACTIVE' where id='${fixtures[at].position.id}'")
        }
        analysisSql(source,"update wishlist_items set category_id='C027',purpose_id='$otherPurpose' where id='${fixtures[20].position.id}'")
        for (s in listOf(scope,ReadScope.Purpose(purpose))) {
            val moved=page(20,s=s);assertEquals(false,moved.anchorResolved);assertEquals(fixtures[21].position.id,moved.resolvedAnchorItemId)
        }
        analysisSql(source,"update wishlist_items set lifecycle_status='DELETED' where owner_id='$owner'")
        val empty=page(20);assertEquals(false,empty.anchorResolved);assertEquals(fixtures[20].position.id,empty.requestedAnchorItemId)
        assertNull(empty.resolvedAnchorItemId);assertTrue(empty.items.isEmpty());assertEquals(0L,empty.totalCount);assertNull(empty.previous);assertNull(empty.next)
    }
}
