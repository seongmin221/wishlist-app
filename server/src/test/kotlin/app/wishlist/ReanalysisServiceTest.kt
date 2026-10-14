package app.wishlist

import app.analysis.AnalysisClaimRepository
import app.analysis.AnalysisLane
import app.analysis.AnalysisResultRepository
import app.analysis.ClaimResult
import app.analysis.ProcessingOutcome
import app.category.CategoryRef
import app.common.FieldChange
import app.testutil.*
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class ReanalysisServiceTest {
    /** A created item whose first generation exhausted its budget. */
    private fun failedItem(source: DataSource, owner: UUID): UUID {
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://shop.example/${UUID.randomUUID()}").createdItemId
        analysisSql(source, "update analysis_jobs set stage='FAILED',attempt_count=3,first_attempt_at=clock_timestamp() where wishlist_item_id='$item'")
        analysisSql(source, "update wishlist_items set analysis_status='FAILED_RETRYABLE',analysis_failure_code='ANALYSIS_RETRYABLE_FAILURE',version=version+1 where id='$item'")
        return item
    }

    private fun jobs(source: DataSource, item: UUID): List<String> = source.connection.use { c ->
        c.prepareStatement("""select generation||':'||stage||':'||attempt_count||':'||coalesce(first_attempt_at::text,'-')
            from analysis_jobs where wishlist_item_id=? order by generation""").use { s ->
            s.setObject(1, item); s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }
    }

    private fun outboxOf(source: DataSource, item: UUID): List<String> = source.connection.use { c ->
        c.prepareStatement("""select o.id||'|'||o.task_name||'|'||coalesce(o.published_at::text,'-') from outbox_events o
            join analysis_jobs j on j.id=o.analysis_job_id where j.wishlist_item_id=? order by j.generation, o.created_at""").use { s ->
            s.setObject(1, item); s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }
    }

    private fun receipts(source: DataSource, owner: UUID) =
        analysisScalar(source, "select count(*) from mutation_receipts where owner_id='$owner' and operation='REANALYZE_WISHLIST_ITEM'")

    private fun expectError(code: String, block: () -> Unit) =
        assertFailsWith<ItemMutationException> { block() }.also { assertEquals(code, it.code) }

    @Test fun `reanalysis starts a new generation atomically and replays without another job`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val dispatched = mutableListOf<UUID>()
        val service = ReanalysisService(source) { dispatched += it }
        val item = failedItem(source, owner)
        val key = UUID.randomUUID()

        val started = assertIs<ReanalysisResult.Started>(service.reanalyze(owner, item, key)).item
        assertEquals(AnalysisStatus.PROCESSING, started.storedState.state.analysisStatus)
        assertEquals(2, started.storedState.currentGeneration)
        assertEquals(3, started.version)
        assertNull(started.analysisFailureCode)
        assertEquals(DeletionImpact(null), started.deletionImpact)
        val jobList = jobs(source, item)
        assertEquals("1:FAILED:3", jobList[0].split(':').take(3).joinToString(":"))
        assertEquals("2:GENERAL_PENDING:0:-", jobList[1], "the new generation starts with a fresh budget")
        val outbox = outboxOf(source, item)
        assertEquals(2, outbox.size)
        val newJob = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item' and generation=2"))
        assertEquals("analysis-$newJob-2", outbox[1].split('|')[1])
        assertEquals(listOf(UUID.fromString(outbox[1].split('|')[0])), dispatched)
        assertEquals("1", receipts(source, owner))

        val replay = assertIs<ReanalysisResult.Replayed>(service.reanalyze(owner, item, key)).item
        assertEquals(3, replay.version)
        assertEquals(jobList, jobs(source, item)); assertEquals(outbox, outboxOf(source, item))
        assertEquals(1, dispatched.size); assertEquals("1", receipts(source, owner))

        val other = failedItem(source, owner)
        expectError("IDEMPOTENCY_KEY_REUSED") { service.reanalyze(owner, other, key) }
        assertEquals("FAILED_RETRYABLE", itemColumns(source, other, "analysis_status")["analysis_status"])
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.reanalyze(UUID.randomUUID(), item, UUID.randomUUID()) }

        ItemMutationService(source).delete(owner, item)
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.reanalyze(owner, item, key) }
        expectError("WISHLIST_ITEM_NOT_FOUND") { service.reanalyze(owner, item, UUID.randomUUID()) }
    }

    @Test fun `reanalysis is refused outside retryable failures and leaves no receipt`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = ReanalysisService(source)
        for (seed in listOf(
            ItemSeed(), ItemSeed(analysis = "PARTIAL", review = "NOT_REQUIRED"), ItemSeed(analysis = "FAILED_TERMINAL", review = "NOT_REQUIRED"),
            ItemSeed(analysis = "PROCESSING", review = "NOT_REQUIRED"), ItemSeed(analysis = "FAILED_RETRYABLE", review = "CONFIRMED", manualCompleted = true),
            ItemSeed(analysis = "FAILED_RETRYABLE", lifecycle = "ARCHIVED", review = "NOT_REQUIRED"),
        )) {
            val item = seedItem(source, owner, seed)
            val error = expectError("WISHLIST_ITEM_STATE_CONFLICT") { service.reanalyze(owner, item, UUID.randomUUID()) }
            assertEquals(3, error.currentVersion)
            assertEquals("3", itemColumns(source, item, "version")["version"])
        }
        assertEquals("0", receipts(source, owner))
    }

    @Test fun `new reanalyses are limited per owner while replays are not counted`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val service = ReanalysisService(source)
        val keys = (1..10).map { UUID.randomUUID() }
        val items = keys.map { key -> failedItem(source, owner).also { service.reanalyze(owner, it, key) } }
        val eleventh = failedItem(source, owner)
        val limited = expectError("WISHLIST_ITEM_REANALYSIS_RATE_LIMITED") { service.reanalyze(owner, eleventh, UUID.randomUUID()) }
        assertTrue(limited.retryAfterSeconds!! in 1..60, "${limited.retryAfterSeconds}")
        assertEquals("FAILED_RETRYABLE", itemColumns(source, eleventh, "analysis_status")["analysis_status"])
        assertEquals("10", receipts(source, owner))
        assertIs<ReanalysisResult.Replayed>(service.reanalyze(owner, items[0], keys[0]))
        val otherOwner = UUID.randomUUID()
        assertIs<ReanalysisResult.Started>(service.reanalyze(otherOwner, failedItem(source, otherOwner), UUID.randomUUID()), "the limit is per owner")
        analysisSql(source, "update mutation_receipts set created_at=created_at-interval '61 seconds' where owner_id='$owner'")
        assertIs<ReanalysisResult.Started>(service.reanalyze(owner, eleventh, UUID.randomUUID()))
    }

    @Test fun `dispatch failures keep the committed attempt and cancellation propagates`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val failing = failedItem(source, owner)
        assertIs<ReanalysisResult.Started>(ReanalysisService(source) { throw IllegalStateException("queue down") }.reanalyze(owner, failing, UUID.randomUUID()))
        assertEquals("PROCESSING", itemColumns(source, failing, "analysis_status")["analysis_status"])

        val cancelled = failedItem(source, owner)
        val key = UUID.randomUUID()
        assertFailsWith<CancellationException> {
            ReanalysisService(source) { throw CancellationException("request cancelled") }.reanalyze(owner, cancelled, key)
        }
        assertEquals("PROCESSING", itemColumns(source, cancelled, "analysis_status")["analysis_status"])
        assertEquals("1", analysisScalar(source, "select count(*) from mutation_receipts where owner_id='$owner' and idempotency_key='$key'"))
        val outbox = outboxOf(source, cancelled)
        assertEquals("-", outbox.last().split('|')[2], "maintenance still owns the unpublished event")
        assertIs<ReanalysisResult.Replayed>(ReanalysisService(source).reanalyze(owner, cancelled, key))
        assertEquals(2, jobs(source, cancelled).size)
        assertEquals(outbox, outboxOf(source, cancelled))
    }

    @Test fun `results of the previous generation are ignored after reanalysis`() = withAnalysisDatabase { source ->
        for (outcome in listOf(ProcessingOutcome.Complete, ProcessingOutcome.Partial, ProcessingOutcome.Retryable, ProcessingOutcome.Terminal)) {
            val owner = UUID.randomUUID()
            val oldClaim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            savePurposeResult(source, oldClaim, null, judged = false)
            // The old execution is still running when the item ends up FAILED_RETRYABLE (for example after recovery).
            analysisSql(source, "update wishlist_items set analysis_status='FAILED_RETRYABLE' where id='${oldClaim.itemId}'")
            ReanalysisService(source).reanalyze(owner, oldClaim.itemId, UUID.randomUUID())
            assertEquals("1:CANCELLED", jobs(source, oldClaim.itemId)[0].split(':').take(2).joinToString(":"))
            val before = itemColumns(source, oldClaim.itemId, "version", "analysis_status", "product_name", "current_generation", "updated_at")
            AnalysisResultRepository(source).finish(oldClaim, outcome)
            assertEquals(before, itemColumns(source, oldClaim.itemId, "version", "analysis_status", "product_name", "current_generation", "updated_at"), "$outcome")
            assertEquals(ClaimResult.Ignored, AnalysisClaimRepository(source).claim(oldClaim.jobId, 1, AnalysisLane.GENERAL))
            assertEquals("2:GENERAL_PENDING", jobs(source, oldClaim.itemId)[1].split(':').take(2).joinToString(":"))
        }
    }

    @Test fun `user values and an explicit purpose removal survive the new generation`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val purpose = insertPurpose(source, owner, "목적", null)
        val item = failedItem(source, owner)
        ItemMutationService(source).edit(owner, item, 2, ItemChanges(name = FieldChange.Set("내 이름"),
            category = FieldChange.Set(CategoryRef.Public("C001")), purpose = FieldChange.Set(null)))
        ReanalysisService(source).reanalyze(owner, item, UUID.randomUUID())
        val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item' and generation=2"))
        val claim = (AnalysisClaimRepository(source).claim(job, 2, AnalysisLane.GENERAL) as ClaimResult.Claimed).claim
        seedV3Snapshot(source, claim, listOf(purpose))
        savePurposeResult(source, claim, purpose.toString(), judged = true)
        AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
        val after = itemColumns(source, item, "analysis_status", "product_name", "name_source", "category_id", "category_source",
            "purpose_id", "purpose_source")
        assertEquals(mapOf("analysis_status" to "READY", "product_name" to "내 이름", "name_source" to "USER", "category_id" to "C001",
            "category_source" to "USER", "purpose_id" to null, "purpose_source" to "USER"), after)
    }
}
