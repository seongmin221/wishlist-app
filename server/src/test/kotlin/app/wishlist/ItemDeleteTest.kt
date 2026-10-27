package app.wishlist

import app.analysis.AnalysisClaimRepository
import app.analysis.AnalysisJobReconciler
import app.analysis.AnalysisLane
import app.analysis.AnalysisResultRepository
import app.analysis.ClaimResult
import app.analysis.ProcessingOutcome
import app.common.FieldChange
import app.http.ApiJson
import app.http.WishlistItemViewMapper
import app.media.MediaUrls
import app.testutil.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class ItemDeleteTest {
    private fun jobStages(source: DataSource, item: UUID): List<String> = source.connection.use { c ->
        c.prepareStatement("select generation||':'||stage||':'||coalesce(execution_token::text,'-') from analysis_jobs where wishlist_item_id=? order by generation")
            .use { s -> s.setObject(1, item); s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
    }

    @Test fun `delete tombstones an active item and repeats as a no-op`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = ItemMutationService(source)
        val purpose = insertPurpose(source, owner, "A", null)
        val (media, _) = insertMedia(source, owner)
        val item = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", media = media, imageSource = "USER"))
        val purposeBefore = purposeColumns(source, purpose)

        assertEquals(DeleteResult.DELETED, service.delete(owner, item))
        val after = itemColumns(source, item, "lifecycle_status", "version", "media_asset_id", "purpose_id", "category_id")
        assertEquals(mapOf("lifecycle_status" to "DELETED", "version" to "4", "media_asset_id" to null,
            "purpose_id" to purpose.toString(), "category_id" to "C026"), after)
        assertNotNull(mediaColumns(source, media)["unreferenced"], "the photo starts its cleanup grace")
        val purposeAfter = purposeColumns(source, purpose)
        assertEquals(purposeBefore["membership"]!!.toInt() + 1, purposeAfter["membership"]!!.toInt())
        assertEquals(purposeBefore["activity"], purposeAfter["activity"])

        assertEquals(DeleteResult.ALREADY_DELETED, service.delete(owner, item))
        assertEquals(after, itemColumns(source, item, "lifecycle_status", "version", "media_asset_id", "purpose_id", "category_id"))
        assertEquals(purposeAfter, purposeColumns(source, purpose))
        assertNull(GetWishlistItemService(source).get(owner, item))

        assertEquals("WISHLIST_ITEM_NOT_FOUND", assertFailsWith<ItemMutationException> { service.delete(UUID.randomUUID(), item) }.code)
        assertEquals("WISHLIST_ITEM_NOT_FOUND", assertFailsWith<ItemMutationException> { service.delete(owner, UUID.randomUUID()) }.code)
        val archived = seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED"))
        val conflict = assertFailsWith<ItemMutationException> { service.delete(owner, archived) }
        assertEquals("WISHLIST_ITEM_STATE_CONFLICT", conflict.code); assertEquals(3, conflict.currentVersion)
    }

    @Test fun `delete cancels only in-progress jobs and keeps finished analysis history`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/p").createdItemId
        // A finished previous generation and a current one, as after one reanalysis.
        analysisSql(source, "update analysis_jobs set stage='FAILED' where wishlist_item_id='$item'")
        analysisSql(source, "insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('${UUID.randomUUID()}','$item',2,'GENERAL_PENDING')")
        analysisSql(source, "update wishlist_items set current_generation=2 where id='$item'")
        assertEquals(DeleteResult.DELETED, ItemMutationService(source).delete(owner, item))
        assertEquals(listOf("1:FAILED:-", "2:CANCELLED:-"), jobStages(source, item))

        for (finished in listOf("COMPLETE", "PARTIAL", "FAILED")) {
            val done = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/$finished").createdItemId
            analysisSql(source, "update analysis_jobs set stage='$finished' where wishlist_item_id='$done'")
            analysisSql(source, "update wishlist_items set analysis_status='READY',category_id='C026',category_source='AI',category_missing_reason=null where id='$done'")
            ItemMutationService(source).delete(owner, done)
            assertEquals(listOf("1:$finished:-"), jobStages(source, done))
        }
    }

    @Test fun `late worker results claims and recovery never touch a deleted item`() = withAnalysisDatabase { source ->
        for (outcome in listOf(ProcessingOutcome.Complete, ProcessingOutcome.Partial, ProcessingOutcome.Retryable, ProcessingOutcome.Terminal)) {
            val owner = UUID.randomUUID()
            val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            savePurposeResult(source, claim, null, judged = false)
            assertEquals(DeleteResult.DELETED, ItemMutationService(source).delete(owner, claim.itemId))
            assertTrue(jobStages(source, claim.itemId).single().startsWith("1:CANCELLED:-"), "token released")
            val before = itemColumns(source, claim.itemId, "lifecycle_status", "version", "analysis_status", "product_name", "updated_at")
            AnalysisResultRepository(source).finish(claim, outcome)
            assertEquals(before, itemColumns(source, claim.itemId, "lifecycle_status", "version", "analysis_status", "product_name", "updated_at"), "$outcome")
            assertEquals(ClaimResult.Ignored, AnalysisClaimRepository(source).claim(claim.jobId, claim.generation, AnalysisLane.GENERAL))
            analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='${claim.jobId}'")
            AnalysisJobReconciler(source).reconcileExpired()
            assertEquals(before, itemColumns(source, claim.itemId, "lifecycle_status", "version", "analysis_status", "product_name", "updated_at"))
            assertEquals("1:CANCELLED:-", jobStages(source, claim.itemId).single())
            assertEquals("1", analysisScalar(source, "select count(*) from outbox_events o join analysis_jobs j on j.id=o.analysis_job_id where j.wishlist_item_id='${claim.itemId}'"), "no retry or recovery outbox")
        }
    }

    @Test fun `single item reads carry the purpose deletion impact and cards do not`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "출퇴근 헤드폰", null)
        val first = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER"))
        val second = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER"))
        seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER", lifecycle = "DELETED"))
        val detail = assertNotNull(GetWishlistItemService(source).get(owner, first))
        assertEquals(DeletionImpact(PurposeImpact(purpose, "출퇴근 헤드폰", 2)), detail.deletionImpact)
        val json = ApiJson.encodeToString(app.http.WishlistItemDto.serializer(), WishlistItemViewMapper.map(detail, MediaUrls(emptyMap())))
        assertTrue(""""deletionImpact":{"purpose":{"id":"$purpose","name":"출퇴근 헤드폰","candidateCount":2,"remainingCount":1}}""" in json, json)

        ItemMutationService(source).delete(owner, second)
        val last = assertNotNull(GetWishlistItemService(source).get(owner, first))
        assertEquals(0, last.deletionImpact!!.purpose!!.candidateCount - 1, "the last candidate leaves an empty purpose")

        val unassigned = seedItem(source, owner, ItemSeed())
        assertEquals(DeletionImpact(null), GetWishlistItemService(source).get(owner, unassigned)!!.deletionImpact)
        val archived = seedItem(source, owner, ItemSeed(lifecycle = "ARCHIVED", purpose = purpose, purposeSource = "USER"))
        val archivedItem = assertNotNull(GetWishlistItemService(source).get(owner, archived))
        assertNull(archivedItem.deletionImpact)
        assertFalse("deletionImpact" in ApiJson.encodeToString(app.http.WishlistItemDto.serializer(), WishlistItemViewMapper.map(archivedItem, MediaUrls(emptyMap()))))

        val card = (WishlistReadService(source).read(owner, ReadQuery(ReadScope.Purpose(purpose), ReadWindow.Page(10))) as ReadResult.Success).page.items.single()
        assertNull(card.deletionImpact)
        assertFalse("deletionImpact" in ApiJson.encodeToString(app.http.WishlistItemDto.serializer(), WishlistItemViewMapper.map(card, MediaUrls(emptyMap()))))

        val key = UUID.randomUUID()
        val created = CreateWishlistItemService(source).create(owner, key, "https://shop.example/new")
        assertEquals(DeletionImpact(null), (created as CreateResult.Created).item.deletionImpact)
        assertEquals(DeletionImpact(null), (CreateWishlistItemService(source).create(owner, key, "https://shop.example/new") as CreateResult.Replayed).item.deletionImpact)
        ItemMutationService(source).delete(owner, created.itemId)
        assertNull((CreateWishlistItemService(source).create(owner, key, "https://shop.example/new") as CreateResult.Replayed).item.deletionImpact)

        val edited = ItemMutationService(source).edit(owner, first, 3, ItemChanges(name = FieldChange.Set("편집")))
        assertEquals(DeletionImpact(PurposeImpact(purpose, "출퇴근 헤드폰", 1)), edited.deletionImpact)
    }

    /** Pauses the detail read between the item and the impact while [change] commits, then returns the detail. */
    private fun readAround(source: DataSource, owner: UUID, item: UUID, change: () -> Unit): WishlistItem {
        val paused = CountDownLatch(1); val resume = CountDownLatch(1)
        val service = GetWishlistItemService(source, beforeImpactRead = { paused.countDown(); check(resume.await(10, TimeUnit.SECONDS)) })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<WishlistItem?> { service.get(owner, item) }
            check(paused.await(10, TimeUnit.SECONDS))
            change()
            resume.countDown()
            return assertNotNull(read.get(20, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun `deletion impact comes from the same snapshot when the item moves away`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val a = insertPurpose(source, owner, "A", null); val b = insertPurpose(source, owner, "B", null)
        val item = seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "USER", review = "CONFIRMED"))
        repeat(2) { seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "USER", review = "CONFIRMED")) }
        val detail = readAround(source, owner, item) {
            ItemMutationService(source).edit(owner, item, 3, ItemChanges(purpose = FieldChange.Set(b)))
        }
        assertEquals(a.toString(), detail.storedState.purposeId)
        assertEquals(DeletionImpact(PurposeImpact(a, "A", 3)), detail.deletionImpact)
    }

    @Test fun `deletion impact comes from the same snapshot when another candidate joins`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val a = insertPurpose(source, owner, "A", null)
        val item = seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "USER", review = "CONFIRMED"))
        repeat(2) { seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "USER", review = "CONFIRMED")) }
        val joining = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        val detail = readAround(source, owner, item) {
            ItemMutationService(source).edit(owner, joining, 3, ItemChanges(purpose = FieldChange.Set(a)))
        }
        assertEquals(DeletionImpact(PurposeImpact(a, "A", 3)), detail.deletionImpact)
    }
}
