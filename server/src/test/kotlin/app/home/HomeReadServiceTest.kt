package app.home

import app.testutil.*
import app.wishlist.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class HomeReadServiceTest {
    @Test fun home_counts_previews_and_action_lists_share_the_same_predicate() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val other=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val complete=WishlistItemState(AnalysisStatus.READY,ReviewStatus.CONFIRMED,LifecycleStatus.ACTIVE,"name","C026",null,null)
        val fixtures=(0 until 21).map { n -> ReadFixture(ReadPosition(time.minusSeconds(n.toLong()),UUID.randomUUID()),when {
            n<5 -> complete.copy(analysisStatus=AnalysisStatus.PROCESSING)
            n<11 -> complete.copy(productName=null)
            n<18 -> complete.copy(reviewStatus=ReviewStatus.PENDING)
            else -> complete
        }) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures);insertReadFixtures(c,other,listOf(fixtures[0].copy(position=ReadPosition(time,UUID.randomUUID())))) }
        val recorded=ReadRecordingDataSource(source)
        val summary=HomeReadService(recorded).get(owner)
        assertEquals(HomeActionGroup.entries,summary.actionGroups.map { it.group })
        assertEquals(listOf(5L,6L,7L),summary.actionGroups.map { it.count })
        for (group in summary.actionGroups) {
            val expected=fixtures.filter { WishlistItemPolicy.evaluate(it.state).homeActionGroup==group.group }.take(4).map { it.position.id }
            assertEquals(expected,group.items.map { it.id })
            val page=assertIs<ReadResult.Success>(WishlistReadService(source).read(owner,ReadQuery(ReadScope.Action(group.group),ReadWindow.Page(100)))).page
            assertEquals(page.totalCount,group.count)
        }
        assertEquals(1,recorded.statements.count { it.contains("i.id=any(?)") })
        val empty=HomeReadService(source).get(UUID.randomUUID())
        assertEquals(listOf(0L,0L,0L),empty.actionGroups.map { it.count });assertTrue(empty.actionGroups.all { it.items.isEmpty() })
    }
    @Test fun recent_purposes_include_empty_and_null_image_candidates() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val purposes=(0 until 4).map { insertPurpose(source,owner,"p$it",null) }
        purposes.forEachIndexed { n,id -> analysisSql(source,"update purposes set activity_at='${time.minusSeconds(n.toLong())}' where id='$id'") }
        val state=WishlistItemState(AnalysisStatus.PROCESSING,ReviewStatus.NOT_REQUIRED,LifecycleStatus.ACTIVE,null,null,CategoryMissingReason.EXTRACTION_UNRESOLVED,null)
        val fixtures=(0 until 5).map { n -> ReadFixture(ReadPosition(time.minusSeconds(n.toLong()),UUID.randomUUID()),state,purposeId=purposes[1],purposeSource=ValueSource.AI,imageUrl=if(n==0) null else "https://example.com/$n") }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures) }
        val summary=HomeReadService(source).get(owner)
        assertEquals(purposes.take(3),summary.recentPurposes.map { it.purpose.id })
        assertEquals(0L,summary.recentPurposes[0].purpose.candidateCount);assertTrue(summary.recentPurposes[0].previews.isEmpty())
        val populated=summary.recentPurposes[1]
        assertEquals(5L,populated.purpose.candidateCount);assertEquals(fixtures.take(4).map { it.position.id },populated.previews.map { it.itemId });assertNull(populated.previews.first().imageUrl)
        val page=assertIs<ReadResult.Success>(WishlistReadService(source).read(owner,ReadQuery(ReadScope.Purpose(purposes[1]),ReadWindow.Page(100)))).page
        assertEquals(page.totalCount,populated.purpose.candidateCount)
    }
}
