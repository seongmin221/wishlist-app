package app.home

import app.testutil.*
import app.wishlist.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class HomeReadSnapshotTest {
    @Test fun snapshot_is_stable_across_concurrent_worker_commit() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val purpose=insertPurpose(source,owner,"before",null);val time=Instant.parse("2026-10-07T10:00:00Z")
        val fixture=ReadFixture(ReadPosition(time,UUID.randomUUID()),WishlistItemState(AnalysisStatus.PROCESSING,ReviewStatus.NOT_REQUIRED,LifecycleStatus.ACTIVE,null,null,CategoryMissingReason.EXTRACTION_UNRESOLVED,null),purposeId=purpose,purposeSource=ValueSource.USER)
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(fixture)) }
        val reached=CountDownLatch(1);val resume=CountDownLatch(1);val first=AtomicBoolean(true)
        val recorded=ReadRecordingDataSource(source) { if(first.compareAndSet(true,false)) { reached.countDown();check(resume.await(10,TimeUnit.SECONDS)) } }
        val pool=Executors.newSingleThreadExecutor()
        try {
            val future=pool.submit<HomeReadSummary> { HomeReadService(recorded).get(owner) }
            assertTrue(reached.await(10,TimeUnit.SECONDS))
            analysisSql(source,"update wishlist_items set analysis_status='READY',product_name='resolved',category_id='C026',category_source='USER',category_missing_reason=null,review_status='CONFIRMED' where id='${fixture.position.id}'")
            analysisSql(source,"update purposes set name='after' where id='$purpose'")
            resume.countDown()
            val old=future.get(10,TimeUnit.SECONDS)
            assertEquals(listOf(1L,0L,0L),old.actionGroups.map { it.count })
            assertEquals(AnalysisStatus.PROCESSING,old.actionGroups.first().items.single().storedState.state.analysisStatus)
            assertEquals("before",old.recentPurposes.single().purpose.input.name)
            val current=HomeReadService(source).get(owner)
            assertEquals(listOf(0L,0L,0L),current.actionGroups.map { it.count });assertEquals("after",current.recentPurposes.single().purpose.input.name)
        } finally { resume.countDown();pool.shutdownNow() }
    }
}
