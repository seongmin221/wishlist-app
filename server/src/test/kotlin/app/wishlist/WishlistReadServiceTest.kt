package app.wishlist

import app.category.CategoryRef
import app.http.*
import io.ktor.http.Parameters
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
    @Test fun empty_page_keeps_an_inclusive_return_cursor_in_both_directions() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val time = Instant.parse("2026-10-07T10:00:00Z")
        val state = WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures = (0..3).map { ReadFixture(ReadPosition(time.minusSeconds(it.toLong()),UUID.randomUUID()),state) }
        source.connection.use { insertReadFixtures(it,owner,fixtures) }
        val scope = ReadScope.Category(CategoryRef.Public("C026"))
        val service = WishlistReadService(source)
        fun read(window: ReadWindow) = assertIs<ReadResult.Success>(service.read(owner,ReadQuery(scope,window))).page
        fun follow(cursor: String): ReadPage {
            val parsed = assertIs<ReadQueryParseResult.Valid>(WishlistReadQueryParser.wishlist(owner,Parameters.build {
                append("categoryId","C026");append("cursor",cursor);append("limit","2")
            }))
            return assertIs<ReadResult.Success>(service.read(owner,parsed.query)).page
        }
        val first = read(ReadWindow.Page(2))
        analysisSql(source,"update wishlist_items set category_id='C027' where id in ('${fixtures[2].position.id}','${fixtures[3].position.id}')")
        val emptyOlder = read(ReadWindow.Page(2,first.next))
        assertTrue(emptyOlder.items.isEmpty());assertEquals(2L,emptyOlder.totalCount)
        val previous = assertNotNull(ReadWindowViewMapper.map(owner,ReadEndpoint.WISHLIST_ITEMS,scope,emptyOlder).previousCursor)
        assertNull(emptyOlder.next)
        assertEquals(first.items,follow(previous).items) // include boundary itself, even if it is the only survivor
        analysisSql(source,"update wishlist_items set category_id='C026' where owner_id='$owner'")
        val second = read(ReadWindow.Page(2,first.next))
        analysisSql(source,"update wishlist_items set lifecycle_status='DELETED' where id in ('${fixtures[0].position.id}','${fixtures[1].position.id}')")
        val emptyNewer = read(ReadWindow.Page(2,second.previous,ReadDirection.NEWER))
        assertTrue(emptyNewer.items.isEmpty());assertNull(emptyNewer.previous)
        val next = assertNotNull(ReadWindowViewMapper.map(owner,ReadEndpoint.WISHLIST_ITEMS,scope,emptyNewer).nextCursor)
        assertEquals(second.items,follow(next).items)
        analysisSql(source,"update wishlist_items set lifecycle_status='DELETED' where owner_id='$owner'")
        val emptyAll = read(ReadWindow.Page(2,second.previous,ReadDirection.NEWER))
        assertNull(emptyAll.previous);assertNull(emptyAll.next);assertEquals(0L,emptyAll.totalCount)
    }

    @Test fun first_page_has_no_opposite_exists_roundtrip() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID()
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        source.connection.use { insertReadFixtures(it,owner,listOf(ReadFixture(ReadPosition(Instant.now(),UUID.randomUUID()),state))) }
        for (scope in listOf(ReadScope.Category(CategoryRef.Public("C026")),ReadScope.PurposeUnassigned,ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW))) {
            val recording=ReadRecordingDataSource(source)
            val page=assertIs<ReadResult.Success>(WishlistReadService(recording).read(owner,ReadQuery(scope,ReadWindow.Page(20)))).page
            assertNull(page.previous);assertEquals(1,page.items.size)
            assertEquals(2,recording.statements.size,"one public-scope window query and one projection")
            val start=recording.statements.size
            val item=page.items.single()
            val anchored=assertIs<ReadResult.Success>(WishlistReadService(recording).read(owner,
                ReadQuery(scope,ReadWindow.Anchor(ReadPosition(item.createdAt,item.id),0,0)))).page
            assertEquals(listOf(item.id),anchored.items.map { it.id })
            assertEquals(2,recording.statements.size-start,"anchor must not use sequential fallback/before/after round trips")
            assertTrue(recording.statements.none { it.startsWith("select exists(") },recording.statements.toString())
        }
    }

    @Test fun action_count_and_window_classify_once_per_request() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures=(0..4).map { ReadFixture(ReadPosition(time.minusSeconds(it.toLong()),UUID.randomUUID()),state) }
        source.connection.use { insertReadFixtures(it,owner,fixtures) }
        for (window in listOf(ReadWindow.Page(2),ReadWindow.Anchor(fixtures[2].position,1,1))) {
            val recording=ReadRecordingDataSource(source)
            val page=assertIs<ReadResult.Success>(WishlistReadService(recording).read(owner,ReadQuery(ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW),window))).page
            assertEquals(5L,page.totalCount)
            assertEquals(1,recording.statements.count { it.contains("classified as materialized") },recording.statements.toString())
        }
    }

    @Test fun action_empty_pages_recover_after_reviews_are_completed_and_count_is_fresh() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures=(0..3).map { ReadFixture(ReadPosition(time.minusSeconds(it.toLong()),UUID.randomUUID()),state) }
        source.connection.use { insertReadFixtures(it,owner,fixtures) }
        val scope=ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW)
        val service=WishlistReadService(source)
        fun read(window: ReadWindow)=assertIs<ReadResult.Success>(service.read(owner,ReadQuery(scope,window))).page
        fun follow(token: String)=assertIs<ReadResult.Success>(service.read(owner,
            assertIs<ReadQueryParseResult.Valid>(WishlistReadQueryParser.action(owner,Parameters.build {
                append("group","CLASSIFICATION_REVIEW");append("cursor",token);append("limit","2")
            })).query)).page
        val first=read(ReadWindow.Page(2))
        analysisSql(source,"update wishlist_items set review_status='CONFIRMED' where id in ('${fixtures[2].position.id}','${fixtures[3].position.id}')")
        val emptyOlder=read(ReadWindow.Page(2,first.next))
        assertTrue(emptyOlder.items.isEmpty());assertEquals(2L,emptyOlder.totalCount)
        assertEquals(first.items,follow(assertNotNull(ReadWindowViewMapper.map(owner,ReadEndpoint.HOME_ACTION_ITEMS,scope,emptyOlder).previousCursor)).items)
        analysisSql(source,"update wishlist_items set review_status='PENDING' where owner_id='$owner'")
        val second=read(ReadWindow.Page(2,first.next))
        analysisSql(source,"update wishlist_items set review_status='DEFERRED' where id in ('${fixtures[0].position.id}','${fixtures[1].position.id}')")
        val emptyNewer=read(ReadWindow.Page(2,second.previous,ReadDirection.NEWER))
        assertTrue(emptyNewer.items.isEmpty());assertEquals(2L,emptyNewer.totalCount)
        assertEquals(second.items,follow(assertNotNull(ReadWindowViewMapper.map(owner,ReadEndpoint.HOME_ACTION_ITEMS,scope,emptyNewer).nextCursor)).items)
        analysisSql(source,"update wishlist_items set review_status='CONFIRMED' where owner_id='$owner'")
        val emptyAll=read(ReadWindow.Page(2,second.previous,ReadDirection.NEWER))
        assertEquals(0L,emptyAll.totalCount);assertNull(emptyAll.previous);assertNull(emptyAll.next)
        val anchor=read(ReadWindow.Anchor(fixtures[1].position,1,1))
        assertEquals(false,anchor.anchorResolved);assertEquals(fixtures[1].position.id,anchor.requestedAnchorItemId)
        assertNull(anchor.resolvedAnchorItemId);assertTrue(anchor.items.isEmpty())
    }

}
