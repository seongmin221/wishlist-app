package app.wishlist

import app.category.CategoryRef
import app.common.FieldChange
import app.media.MediaCleanup
import app.media.MediaCleanupReport
import app.testutil.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class ItemMutationConcurrencyTest {
    /** Runs [left] and [right] from a shared start; returns each outcome or the thrown exception. */
    private fun <A, B> race(left: () -> A, right: () -> B): Pair<Result<A>, Result<B>> {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(left) })
            val b = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching(right) })
            return a.get(30, TimeUnit.SECONDS) to b.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    private fun Result<*>.code(): String = exceptionOrNull()?.let { (it as ItemMutationException).code } ?: "OK"

    @Test fun `concurrent edits and reviews with one version let exactly one win`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = ItemMutationService(source)
        repeat(5) { round ->
            val item = seedItem(source, owner)
            val (a, b) = race(
                { service.edit(owner, item, 3, ItemChanges(name = FieldChange.Set("A$round"))) },
                { if (round % 2 == 0) service.edit(owner, item, 3, ItemChanges(name = FieldChange.Set("B$round")))
                  else service.decideReview(owner, item, 3, ReviewDecision.CONFIRM, ItemChanges()) },
            )
            assertEquals(listOf("OK", "WISHLIST_ITEM_VERSION_CONFLICT"), listOf(a.code(), b.code()).sorted(), "round $round")
            assertEquals("4", itemColumns(source, item, "version")["version"])
            val loser = (a.exceptionOrNull() ?: b.exceptionOrNull()) as ItemMutationException
            assertEquals(4, loser.currentVersion)
        }
    }

    @Test fun `concurrent deletes change the item once and both succeed`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "A", null)
        val item = seedItem(source, owner, ItemSeed(purpose = purpose, purposeSource = "USER"))
        val before = purposeColumns(source, purpose)["membership"]!!.toInt()
        val (a, b) = race({ ItemMutationService(source).delete(owner, item) }, { ItemMutationService(source).delete(owner, item) })
        assertEquals(setOf(DeleteResult.DELETED, DeleteResult.ALREADY_DELETED), setOf(a.getOrThrow(), b.getOrThrow()))
        assertEquals("4", itemColumns(source, item, "version")["version"])
        assertEquals(before + 1, purposeColumns(source, purpose)["membership"]!!.toInt())
    }

    private fun failedItem(source: DataSource, owner: UUID): UUID {
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/${UUID.randomUUID()}").createdItemId
        analysisSql(source, "update analysis_jobs set stage='FAILED' where wishlist_item_id='$item'")
        analysisSql(source, "update wishlist_items set analysis_status='FAILED_RETRYABLE' where id='$item'")
        return item
    }

    private fun jobCount(source: DataSource, item: UUID) = analysisScalar(source, "select count(*) from analysis_jobs where wishlist_item_id='$item'")

    @Test fun `concurrent reanalysis with one key creates one job`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(3) {
            val item = failedItem(source, owner)
            val key = UUID.randomUUID()
            val (a, b) = race({ ReanalysisService(source).reanalyze(owner, item, key) }, { ReanalysisService(source).reanalyze(owner, item, key) })
            assertEquals(setOf(ReanalysisResult.Started::class, ReanalysisResult.Replayed::class), setOf(a.getOrThrow()::class, b.getOrThrow()::class))
            assertEquals("2", jobCount(source, item))
            assertEquals("2", analysisScalar(source, "select count(*) from outbox_events o join analysis_jobs j on j.id=o.analysis_job_id where j.wishlist_item_id='$item'"))
        }
    }

    @Test fun `reanalysis racing a delete never leaves a live job on a deleted item`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(4) {
            val item = failedItem(source, owner)
            val (started, deleted) = race({ ReanalysisService(source).reanalyze(owner, item, UUID.randomUUID()) }, { ItemMutationService(source).delete(owner, item) })
            assertEquals(DeleteResult.DELETED, deleted.getOrThrow())
            if (started.isFailure) assertEquals("WISHLIST_ITEM_NOT_FOUND", started.code())
            assertEquals("DELETED", itemColumns(source, item, "lifecycle_status")["lifecycle_status"])
            assertEquals("0", analysisScalar(source, "select count(*) from analysis_jobs where wishlist_item_id='$item' and stage in ('GENERAL_PENDING','GENERAL_RUNNING','BROWSER_PENDING','BROWSER_RUNNING')"))
        }
    }

    @Test fun `two photo links for the same new media let only one item use it`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val (media, _) = insertMedia(source, owner)
        val first = seedItem(source, owner, ItemSeed(review = "CONFIRMED")); val second = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        val (a, b) = race(
            { ItemMutationService(source).edit(owner, first, 3, ItemChanges(media = FieldChange.Set(media))) },
            { ItemMutationService(source).edit(owner, second, 3, ItemChanges(media = FieldChange.Set(media))) },
        )
        assertEquals(listOf("MEDIA_NOT_AVAILABLE", "OK"), listOf(a.code(), b.code()).sorted())
        assertEquals("1", analysisScalar(source, "select count(*) from wishlist_items where media_asset_id='$media'"))
    }

    @Test fun `photo linking and media cleanup never leave a deleting media referenced`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        val farFuture = System.nanoTime() + 60_000_000_000
        fun staleReady() = insertMedia(source, owner).let { (id, key) ->
            storage.put(key, byteArrayOf(1))
            analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours' where id='$id'")
            id
        }
        fun status(id: UUID) = mediaColumns(source, id)["status"]

        // The edit holds the media lock first: cleanup skips the row and the link commits.
        val first = staleReady(); val firstItem = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        val locked = CountDownLatch(1); val hold = CountDownLatch(1)
        val holding = ItemMutationService(source, afterMediaLock = { locked.countDown(); check(hold.await(10, TimeUnit.SECONDS)) })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val linking = pool.submit(Callable { holding.edit(owner, firstItem, 3, ItemChanges(media = FieldChange.Set(first))) })
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))
            hold.countDown()
            assertEquals(first, linking.get(10, TimeUnit.SECONDS).mediaId)
        } finally { hold.countDown(); pool.shutdown() }
        assertEquals("READY", status(first))
        assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))

        // Cleanup leases first: the edit sees DELETING and refuses.
        val second = staleReady(); val secondItem = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        var refused: String? = null
        val cleanup = MediaCleanup(source, storage, beforeDelete = { id ->
            if (id == second) refused = runCatching { ItemMutationService(source).edit(owner, secondItem, 3, ItemChanges(media = FieldChange.Set(second))) }
                .exceptionOrNull()?.let { (it as ItemMutationException).code }
        })
        assertEquals(MediaCleanupReport(1, 0), cleanup.run(farFuture))
        assertEquals("MEDIA_NOT_AVAILABLE", refused)
        assertEquals("DELETED", status(second))
        assertNull(itemColumns(source, secondItem, "media_asset_id")["media_asset_id"])

        // Discovery saw the row unreferenced, then the edit committed before the lease. The link clears unreferenced_since,
        // so the lease's READY condition already drops the row; the separate reference re-check for a link that kept a
        // stale unreferenced_since is B6's MediaCleanupTest case.
        val third = staleReady(); val thirdItem = seedItem(source, owner, ItemSeed(review = "CONFIRMED"))
        val racing = MediaCleanup(source, storage, beforeLease = { id ->
            if (id == third) ItemMutationService(source).edit(owner, thirdItem, 3, ItemChanges(media = FieldChange.Set(third)))
        })
        assertEquals(MediaCleanupReport(0, 0), racing.run(farFuture))
        assertEquals("READY", status(third))
        assertEquals(third.toString(), itemColumns(source, thirdItem, "media_asset_id")["media_asset_id"])
    }

    @Test fun `concurrent purpose moves keep membership versions consistent`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val a = insertPurpose(source, owner, "A", null); val b = insertPurpose(source, owner, "B", null)
        val items = (1..4).map { seedItem(source, owner, ItemSeed(purpose = a, purposeSource = "USER", review = "CONFIRMED")) }
        val (x, y) = race(
            { items.take(2).forEach { ItemMutationService(source).edit(owner, it, 3, ItemChanges(purpose = FieldChange.Set(b))) } },
            { items.drop(2).forEach { ItemMutationService(source).edit(owner, it, 3, ItemChanges(purpose = FieldChange.Set(b),
                category = FieldChange.Set(CategoryRef.Public("C001")))) } },
        )
        x.getOrThrow(); y.getOrThrow()
        assertEquals("5", purposeColumns(source, a)["membership"]); assertEquals("5", purposeColumns(source, b)["membership"])
        assertEquals("0", analysisScalar(source, "select count(*) from wishlist_items where purpose_id='$a' and lifecycle_status='ACTIVE'"))
    }
}
