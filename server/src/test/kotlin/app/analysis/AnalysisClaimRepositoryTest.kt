package app.analysis

import app.DatabaseFactory
import app.testutil.PostgresTestContainer
import app.testutil.createdItemId
import app.wishlist.CreateWishlistItemService
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

class AnalysisClaimRepositoryTest {
    @Test fun `deleted pending jobs cancel without attempts item updates or additional events`() = app.testutil.withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = app.testutil.newFinishJob(source, lane)
            app.testutil.analysisSql(source, "update wishlist_items set lifecycle_status='DELETED' where id='${job.itemId}'")
            val before = app.testutil.finishSnapshot(source, job)
            assertEquals(ClaimResult.Ignored, AnalysisClaimRepository(source).claim(job.jobId, 1, lane))
            assertEquals("CANCELLED", app.testutil.analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            val after = app.testutil.finishSnapshot(source, job)
            assertEquals(before[0], after[0])
            assertEquals(before[2], after[2])
            assertEquals("0", app.testutil.analysisScalar(source, "select attempt_count+browser_attempt_count from analysis_jobs where id='${job.jobId}'"))
        }
    }

    @Test
    fun `concurrent lane claims have one winner and consume one attempt`() = withDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = createJob(source, lane)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val barrierSource = object : DataSource by source {
                override fun getConnection(): Connection {
                    val connection = source.connection
                    try {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        return connection
                    } catch (cause: Throwable) { connection.close(); throw cause }
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls = (1..2).map {
                    pool.submit<ClaimResult> {
                        AnalysisClaimRepository(barrierSource).claim(job.jobId, 1, lane)
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val results = calls.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is ClaimResult.Claimed })
                assertEquals(1, results.count { it == ClaimResult.Ignored })
                val claim = (results.single { it is ClaimResult.Claimed } as ClaimResult.Claimed).claim
                assertEquals(job.ownerId, claim.ownerId)
                assertEquals(job.itemId, claim.itemId)
                assertEquals(1, claim.expectedItemVersion)
                assertEquals(1, claim.generation)
                assertEquals(lane, claim.lane)
                source.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("select *, lease_until > clock_timestamp() and lease_until <= clock_timestamp()+interval '120 seconds' as valid_lease from analysis_jobs where id='${job.jobId}'").use { r ->
                            check(r.next())
                            assertEquals("${lane.name}_RUNNING", r.getString("stage"))
                            assertEquals(1, r.getInt(if (lane == AnalysisLane.GENERAL) "attempt_count" else "browser_attempt_count"))
                            assertEquals(0, r.getInt(if (lane == AnalysisLane.GENERAL) "browser_attempt_count" else "attempt_count"))
                            assertEquals(claim.executionToken, r.getObject("execution_token", UUID::class.java))
                            assertEquals(1, r.getInt("claimed_item_version"))
                            assertEquals(claim.leaseUntil, r.getTimestamp("lease_until").toInstant())
                            assertTrue(r.getBoolean("valid_lease"))
                        }
                    }
                }
                assertTrue(guard(source, claim))
            } finally { start.countDown(); pool.shutdownNow() }
        }
    }

    @Test
    fun `ineligible items and lanes do not claim or exhaust another generation`() = withDatabase { source ->
        val assignments = listOf("lifecycle_status='DELETED'", "lifecycle_status='ARCHIVED'", "manual_completion_at=now()", "analysis_status='READY'", "current_generation=2")
        for (assignment in assignments) {
            val job = createJob(source)
            sql(source, "update wishlist_items set $assignment where id='${job.itemId}'")
            sql(source, "update analysis_jobs set attempt_count=3 where id='${job.jobId}'")
            assertEquals(ClaimResult.Ignored, AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.GENERAL))
            assertEquals(if (assignment == "lifecycle_status='DELETED'") "CANCELLED" else "GENERAL_PENDING", scalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            assertEquals("1", scalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        }
        val job = createJob(source)
        val repository = AnalysisClaimRepository(source)
        assertEquals(ClaimResult.Ignored, repository.claim(UUID.randomUUID(), 1, AnalysisLane.GENERAL))
        assertEquals(ClaimResult.Ignored, repository.claim(job.jobId, 2, AnalysisLane.GENERAL))
        assertEquals(ClaimResult.Ignored, repository.claim(job.jobId, 1, AnalysisLane.BROWSER))
        sql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=false where id='${job.jobId}'")
        assertEquals(ClaimResult.Ignored, repository.claim(job.jobId, 1, AnalysisLane.BROWSER))
        assertEquals("0", scalar(source, "select browser_attempt_count from analysis_jobs where id='${job.jobId}'"))
    }

    @Test
    fun `only current generation job can claim when multiple jobs exist`() = withDatabase { source ->
        val job = createJob(source)
        val currentJob = UUID.randomUUID()
        sql(source, "update wishlist_items set current_generation=2 where id='${job.itemId}'")
        sql(source, "insert into analysis_jobs(id,wishlist_item_id,generation,stage) values ('$currentJob','${job.itemId}',2,'GENERAL_PENDING')")
        val repository = AnalysisClaimRepository(source)
        assertEquals(ClaimResult.Ignored, repository.claim(job.jobId, 1, AnalysisLane.GENERAL))
        val claim = assertIs<ClaimResult.Claimed>(repository.claim(currentJob, 2, AnalysisLane.GENERAL)).claim
        assertEquals(2, claim.generation)
        assertEquals("0", scalar(source, "select attempt_count from analysis_jobs where id='${job.jobId}'"))
        assertTrue(guard(source, claim))
    }

    @Test
    fun `guard rejects changed identity state version lane token and expired lease without mutation`() = withDatabase { source ->
        val job = createJob(source)
        val claim = assertIs<ClaimResult.Claimed>(AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.GENERAL)).claim
        for (invalid in listOf(
            claim.copy(ownerId = UUID.randomUUID()), claim.copy(itemId = UUID.randomUUID()), claim.copy(jobId = UUID.randomUUID()),
            claim.copy(generation = 2), claim.copy(executionToken = UUID.randomUUID()), claim.copy(lane = AnalysisLane.BROWSER),
            claim.copy(expectedItemVersion = 2),
        )) assertFalse(guard(source, invalid))
        val otherJob = createJob(source)
        val otherClaim = assertIs<ClaimResult.Claimed>(AnalysisClaimRepository(source).claim(otherJob.jobId, 1, AnalysisLane.GENERAL)).claim
        assertFalse(guard(source, otherClaim.copy(itemId = job.itemId, ownerId = job.ownerId)))
        val itemChanges = listOf("version=2", "current_generation=2", "lifecycle_status='DELETED'", "lifecycle_status='ARCHIVED'", "analysis_status='PARTIAL'", "manual_completion_at=now()")
        for (change in itemChanges) {
            sql(source, "update wishlist_items set $change where id='${job.itemId}'")
            assertFalse(guard(source, claim), change)
            sql(source, "update wishlist_items set version=1,current_generation=1,lifecycle_status='ACTIVE',analysis_status='PROCESSING',manual_completion_at=null where id='${job.itemId}'")
        }
        val jobChanges = listOf("stage='CANCELLED'", "execution_token='${UUID.randomUUID()}'", "generation=2", "claimed_item_version=2", "claimed_item_version=null", "lease_until=null", "lease_until=clock_timestamp()-interval '1 second'")
        for (change in jobChanges) {
            sql(source, "update analysis_jobs set $change where id='${job.jobId}'")
            val before = scalar(source, "select to_jsonb(j)::text from analysis_jobs j where id='${job.jobId}'")
            assertFalse(guard(source, claim), change)
            assertEquals(before, scalar(source, "select to_jsonb(j)::text from analysis_jobs j where id='${job.jobId}'"))
            sql(source, "update analysis_jobs set stage='GENERAL_RUNNING',generation=1,execution_token='${claim.executionToken}',claimed_item_version=1,lease_until=clock_timestamp()+interval '120 seconds' where id='${job.jobId}'")
        }
        assertTrue(guard(source, claim))
    }

    @Test
    fun `retry rotates execution and clears lane pending results but retains candidate snapshot`() = withDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val job = createJob(source, lane)
            val repository = AnalysisClaimRepository(source)
            val old = assertIs<ClaimResult.Claimed>(repository.claim(job.jobId, 1, lane)).claim
            sql(source, """update analysis_jobs set stage='${lane.name}_PENDING',lease_until=clock_timestamp()-interval '1 second',
                pending_product_name='old-name',pending_product_description='old-description',pending_product_image_url='old-image',pending_canonical_url='old-url',
                pending_category_id='old-category',pending_purpose_id='old-purpose',pending_failure_code='old-failure',pending_purpose_judged=true,candidate_snapshot_json='fixed-snapshot'
                where id='${job.jobId}'""".trimIndent())
            val current = assertIs<ClaimResult.Claimed>(repository.claim(job.jobId, 1, lane)).claim
            assertNotEquals(old.executionToken, current.executionToken)
            assertFalse(guard(source, old))
            assertTrue(guard(source, current))
            assertEquals("fixed-snapshot", scalar(source, "select candidate_snapshot_json from analysis_jobs where id='${job.jobId}'"))
            assertEquals("f", scalar(source, "select pending_purpose_judged from analysis_jobs where id='${job.jobId}'"))
            for (column in listOf("pending_category_id", "pending_purpose_id", "pending_failure_code")) {
                assertNull(scalar(source, "select $column from analysis_jobs where id='${job.jobId}'"))
            }
            for ((column, oldValue) in listOf("pending_product_name" to "old-name", "pending_product_description" to "old-description", "pending_product_image_url" to "old-image", "pending_canonical_url" to "old-url")) {
                assertEquals(if (lane == AnalysisLane.BROWSER) oldValue else null, scalar(source, "select $column from analysis_jobs where id='${job.jobId}'"))
            }
        }
    }

    @Test
    fun `attempt and thirty minute limits exhaust only eligible lane without another attempt`() = withDatabase { source ->
        for (lane in AnalysisLane.entries) for (limit in listOf("attempt", "time")) {
            val job = createJob(source, lane)
            val count = if (lane == AnalysisLane.GENERAL) "attempt_count" else "browser_attempt_count"
            val first = if (lane == AnalysisLane.GENERAL) "first_attempt_at" else "first_browser_attempt_at"
            val attempts = if (limit == "attempt") 3 else 1
            sql(source, "update analysis_jobs set $count=$attempts,$first=clock_timestamp()-interval '${if (limit == "time") 31 else 1} minutes' where id='${job.jobId}'")
            val repository = AnalysisClaimRepository(source)
            assertEquals(ClaimResult.Exhausted, repository.claim(job.jobId, 1, lane))
            assertEquals("$attempts", scalar(source, "select $count from analysis_jobs where id='${job.jobId}'"))
            assertEquals("FAILED", scalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            assertEquals("FAILED_RETRYABLE", scalar(source, "select analysis_status from wishlist_items where id='${job.itemId}'"))
            assertEquals("2", scalar(source, "select version from wishlist_items where id='${job.itemId}'"))
            assertEquals(ClaimResult.Ignored, repository.claim(job.jobId, 1, lane))
            assertEquals("2", scalar(source, "select version from wishlist_items where id='${job.itemId}'"))
        }
    }

    @Test
    fun `guard leaves locks and transaction ownership with its caller`() = withDatabase { source ->
        val job = createJob(source)
        val claim = assertIs<ClaimResult.Claimed>(AnalysisClaimRepository(source).claim(job.jobId, 1, AnalysisLane.GENERAL)).claim
        source.connection.use { held ->
            assertFailsWith<IllegalArgumentException> { AnalysisWriteGuard.lockCurrent(held, claim) }
            held.autoCommit = false
            assertTrue(AnalysisWriteGuard.lockCurrent(held, claim))
            for (target in listOf("wishlist_items" to job.itemId, "analysis_jobs" to job.jobId)) {
                source.connection.use { other ->
                    other.createStatement().use { s ->
                        s.execute("set lock_timeout='100ms'")
                        val error = assertFailsWith<SQLException> { s.executeUpdate("update ${target.first} set updated_at=now() where id='${target.second}'") }
                        assertEquals("55P03", error.sqlState)
                    }
                }
            }
            held.rollback()
            sql(source, "update wishlist_items set product_name='caller completed' where id='${job.itemId}'")
            assertEquals("caller completed", scalar(source, "select product_name from wishlist_items where id='${job.itemId}'"))
        }
    }

    private data class Job(val jobId: UUID, val itemId: UUID, val ownerId: UUID)
    private fun createJob(source: DataSource, lane: AnalysisLane = AnalysisLane.GENERAL): Job {
        val owner = UUID.randomUUID()
        val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").createdItemId
        val id = UUID.fromString(scalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
        if (lane == AnalysisLane.BROWSER) sql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true where id='$id'")
        return Job(id, item, owner)
    }
    private fun guard(source: DataSource, claim: AnalysisClaim): Boolean = source.connection.use { c ->
        c.autoCommit = false
        try { AnalysisWriteGuard.lockCurrent(c, claim) } finally { c.rollback() }
    }
    private fun sql(source: DataSource, sql: String) = source.connection.use { c -> c.createStatement().use { it.executeUpdate(sql) }; Unit }
    private fun scalar(source: DataSource, sql: String): String? = source.connection.use { c ->
        c.createStatement().use { it.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } }
    }
    private fun withDatabase(block: (DataSource) -> Unit) = PostgresTestContainer().use { database ->
        database.start()
        DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
        block(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password))
    }
}
