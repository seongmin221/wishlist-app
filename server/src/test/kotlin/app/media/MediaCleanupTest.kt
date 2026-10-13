package app.media

import app.persistence.OwnerStructureLock
import app.persistence.inTransaction
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*
import kotlinx.serialization.json.*

class MediaCleanupTest {
    private val farFuture get() = System.nanoTime() + 60_000_000_000

    private fun status(source: DataSource, id: UUID) = analysisScalar(source, "select status from media_assets where id='$id'")
    private fun item(source: DataSource, owner: UUID, media: UUID, lifecycle: String = "ACTIVE", updatedAgo: String = "0 seconds"): UUID {
        val id = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/${UUID.randomUUID()}").createdItemId
        analysisSql(source, "update wishlist_items set media_asset_id='$media',lifecycle_status='$lifecycle',updated_at=clock_timestamp()-interval '$updatedAgo' where id='$id'")
        return id
    }

    /** B7-style link: owner → item → media row lock, link only a READY media and touch the media row. */
    private fun link(source: DataSource, owner: UUID, item: UUID, media: UUID, hold: CountDownLatch? = null, locked: CountDownLatch? = null): Boolean =
        source.inTransaction { c ->
            OwnerStructureLock.lock(c, owner)
            c.prepareStatement("select id from wishlist_items where id=? for update").use { s -> s.setObject(1, item); s.executeQuery().close() }
            val ready = c.prepareStatement("select status from media_assets where id=? for update").use { s ->
                s.setObject(1, media); s.executeQuery().use { r -> r.next() && r.getString(1) == "READY" }
            }
            locked?.countDown(); hold?.await(5, TimeUnit.SECONDS)
            if (ready) {
                c.prepareStatement("update wishlist_items set media_asset_id=? where id=?").use { s -> s.setObject(1, media); s.setObject(2, item); s.executeUpdate() }
                c.prepareStatement("update media_assets set updated_at=clock_timestamp() where id=?").use { s -> s.setObject(1, media); s.executeUpdate() }
            }
            ready
        }

    @Test fun `deletes expired failed and unreferenced media after twenty four hours and keeps references`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        fun media(status: String, set: String): UUID = insertMedia(source, owner, status).first.also { id ->
            analysisSql(source, "update media_assets set $set where id='$id'")
            storage.put(MediaKeys.objectKey(owner, id), ByteArray(1))
        }
        val keepPending = media("PENDING", "upload_expires_at=clock_timestamp()-interval '23 hours 59 minutes'")
        val dropPending = media("PENDING", "upload_expires_at=clock_timestamp()-interval '24 hours 1 second'")
        val keepFailed = media("FAILED", "failed_at=clock_timestamp()-interval '23 hours 59 minutes'")
        val dropFailed = media("FAILED", "failed_at=clock_timestamp()-interval '24 hours 1 second'")
        val old = "unreferenced_since=clock_timestamp()-interval '25 hours'"
        val dropReady = media("READY", old)
        val keepFresh = media("READY", "unreferenced_since=clock_timestamp()-interval '23 hours'")
        val keepReleased = media("READY", "ready_at=clock_timestamp()-interval '50 hours',unreferenced_since=clock_timestamp()-interval '1 hour'")
        val keepLinked = media("READY", "ready_at=clock_timestamp()-interval '50 hours',unreferenced_since=null").also { item(source, owner, it) }
        // A stale unreferenced_since on a linked row (a missed link update) is still protected by the reference re-check.
        val keepActive = media("READY", old).also { item(source, owner, it) }
        val keepArchived = media("READY", old).also { item(source, owner, it, "ARCHIVED") }
        val keepRecentDelete = media("READY", old).also { item(source, owner, it, "DELETED", "1 hour") }
        val dropOldDelete = media("READY", old).also { item(source, owner, it, "DELETED", "25 hours") }

        val report = MediaCleanup(source, storage).run(farFuture)
        val dropped = listOf(dropPending, dropFailed, dropReady, dropOldDelete)
        assertEquals(MediaCleanupReport(dropped.size, 0), report)
        for (id in dropped) {
            assertEquals("DELETED", status(source, id))
            assertEquals("t", analysisScalar(source, "select deleted_at is not null from media_assets where id='$id'"))
            assertTrue(MediaKeys.objectKey(owner, id) in storage.deletes)
        }
        for (id in listOf(keepPending, keepFailed, keepFresh, keepReleased, keepLinked, keepActive, keepArchived, keepRecentDelete))
            assertNotEquals("DELETED", status(source, id), id.toString())
        assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))

        // A tombstone keeps the key: the same intent key is now expired, not a new media.
        val key = UUID.fromString(analysisScalar(source, "select idempotency_key from media_assets where id='$dropPending'"))
        val service = MediaService(source, storage, storage.signer, MediaImageValidator(), MediaSigningExecutor())
        val declared = analysisScalar(source, "select declared_byte_size||','||declared_width||','||declared_height from media_assets where id='$dropPending'")!!.split(",").map(String::toInt)
        analysisSql(source, "update media_assets set request_fingerprint='${IntentRequest(MediaLimits.CONTENT_TYPE, declared[0], declared[1], declared[2]).fingerprint()}' where id='$dropPending'")
        assertEquals("MEDIA_UPLOAD_EXPIRED", assertFailsWith<MediaException> {
            service.createIntent(owner, key, IntentRequest(MediaLimits.CONTENT_TYPE, declared[0], declared[1], declared[2]))
        }.code)
    }

    @Test fun `failed deletes keep a lease and are retried after it`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage().apply { failDelete = true }
        val owner = UUID.randomUUID()
        val (id, _) = insertMedia(source, owner)
        analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours' where id='$id'")
        assertEquals(MediaCleanupReport(0, 1), MediaCleanup(source, storage).run(farFuture))
        assertEquals("DELETING", status(source, id))
        storage.failDelete = false
        assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))
        analysisSql(source, "update media_assets set updated_at=clock_timestamp()-interval '3 minutes' where id='$id'")
        assertEquals(MediaCleanupReport(1, 0), MediaCleanup(source, storage).run(farFuture))
        assertEquals("DELETED", status(source, id))
    }

    @Test fun `batches fifty candidates and stops at the deadline`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        repeat(55) { insertMedia(source, owner) }
        analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours'")
        assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(System.nanoTime()))
        assertEquals(0, storage.deleteCalls.get())
        assertEquals(MediaCleanupReport(50, 0), MediaCleanup(source, storage).run(farFuture))
        assertEquals(MediaCleanupReport(5, 0), MediaCleanup(source, storage).run(farFuture))
    }

    @Test fun `two concurrent runs delete each object once`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage().apply { deleteDelayMillis = 50 }
        val owner = UUID.randomUUID()
        repeat(10) { insertMedia(source, owner) }
        analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours'")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reports = List(2) { pool.submit(Callable { start.await(); MediaCleanup(source, storage).run(farFuture) }) }
            start.countDown()
            assertEquals(10, reports.sumOf { it.get(20, TimeUnit.SECONDS).deleted })
        } finally { pool.shutdown() }
        assertEquals(10, storage.deleteCalls.get())
    }

    @Test fun `cleanup and linking never leave a deleting media referenced`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage()
        val owner = UUID.randomUUID()
        fun staleReady() = insertMedia(source, owner).first.also {
            analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours' where id='$it'")
        }
        fun freeItem() = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/${UUID.randomUUID()}").createdItemId

        // Link holds the media lock first: cleanup skips the row, the link commits.
        val first = staleReady(); val firstItem = freeItem()
        val hold = CountDownLatch(1); val locked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val linking = pool.submit(Callable { link(source, owner, firstItem, first, hold, locked) })
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))
            hold.countDown()
            assertTrue(linking.get(5, TimeUnit.SECONDS))
        } finally { hold.countDown(); pool.shutdown() }
        assertEquals("READY", status(source, first))
        assertEquals(MediaCleanupReport(0, 0), MediaCleanup(source, storage).run(farFuture))
        assertEquals("READY", status(source, first))

        // Cleanup leases first: the link sees DELETING and refuses.
        val second = staleReady(); val secondItem = freeItem()
        var linked: Boolean? = null
        val cleanup = MediaCleanup(source, storage, beforeDelete = { id -> if (id == second) linked = link(source, owner, secondItem, second) })
        assertEquals(MediaCleanupReport(1, 0), cleanup.run(farFuture))
        assertEquals(false, linked)

        // Discovery saw the row unreferenced, then a link committed before the lease: the lease re-check sees it.
        val third = staleReady(); val thirdItem = freeItem()
        val racing = MediaCleanup(source, storage, beforeLease = { id -> if (id == third) assertTrue(link(source, owner, thirdItem, third)) })
        assertEquals(MediaCleanupReport(0, 0), racing.run(farFuture))
        assertEquals("READY", status(source, third))
        assertEquals(third.toString(), analysisScalar(source, "select media_asset_id from wishlist_items where id='$thirdItem'"))
        assertEquals("DELETED", status(source, second))
        assertNull(analysisScalar(source, "select media_asset_id from wishlist_items where id='$secondItem'"))
    }

    @Test fun `linked images are not in the ready discovery index at all`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        repeat(200) {
            val (id, _) = insertMedia(source, owner)
            analysisSql(source, "update media_assets set ready_at=clock_timestamp()-interval '50 hours',unreferenced_since=null where id='$id'")
            item(source, owner, id)
        }
        val (candidate, _) = insertMedia(source, owner)
        analysisSql(source, "update media_assets set unreferenced_since=clock_timestamp()-interval '25 hours' where id='$candidate'")
        analysisSql(source, "analyze media_assets")
        val plan = source.connection.use { c ->
            val query = MediaReferences.discovery("m")
            c.prepareStatement("explain (analyze, format json) " + query.sql).use { s ->
                for (index in 1..query.limits) s.setInt(index, 50)
                s.executeQuery().use { r -> check(r.next()); r.getString(1) }
            }
        }
        fun nodes(e: JsonElement): List<JsonObject> = when (e) {
            is JsonObject -> listOf(e) + e.values.flatMap(::nodes)
            is JsonArray -> e.flatMap(::nodes)
            else -> emptyList()
        }
        val ready = nodes(Json.parseToJsonElement(plan)).filter { it["Index Name"]?.jsonPrimitive?.content == "media_assets_cleanup_ready_idx" }
        assertEquals(1, ready.size, "READY branch must use its partial index: $plan")
        // Linked rows carry no unreferenced_since, so the index yields only the one candidate.
        assertEquals(1, ready.single()["Actual Rows"]!!.jsonPrimitive.int, plan)
        assertEquals(MediaCleanupReport(1, 0), MediaCleanup(source, InMemoryMediaStorage()).run(farFuture))
        assertEquals("DELETED", status(source, candidate))
    }

    @Test fun `discovery binds exactly as many limits as it has placeholders`() {
        val query = MediaReferences.discovery("m")
        assertEquals(query.sql.count { it == '?' }, query.limits)
    }
}
