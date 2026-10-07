package app.testutil

import app.DatabaseFactory
import app.analysis.AnalysisClaim
import app.analysis.AnalysisClaimRepository
import app.analysis.AnalysisLane
import app.analysis.ClaimResult
import app.wishlist.CreateResult
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Fixture creations must produce a fresh item; any other outcome fails the test here. */
val CreateResult.createdItemId: UUID get() = kotlin.test.assertIs<CreateResult.Created>(this).itemId

fun withAnalysisDatabase(block: (DataSource) -> Unit) = PostgresTestContainer().use { db ->
    db.start()
    DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
    block(DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password))
}

fun claimJob(source: DataSource, jobId: UUID, lane: AnalysisLane = AnalysisLane.GENERAL): AnalysisClaim =
    (AnalysisClaimRepository(source).claim(jobId, 1, lane) as ClaimResult.Claimed).claim

fun newAnalysisClaim(source: DataSource, lane: AnalysisLane = AnalysisLane.GENERAL): AnalysisClaim {
    val item = CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item").createdItemId
    val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
    if (lane == AnalysisLane.BROWSER) analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true where id='$job'")
    return claimJob(source, job, lane)
}

fun analysisSql(source: DataSource, sql: String) = source.connection.use { c ->
    c.createStatement().use { it.execute("set statement_timeout='5s'"); it.executeUpdate(sql) }; Unit
}

fun analysisScalar(source: DataSource, sql: String): String? = source.connection.use { c ->
    c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) } }
}

fun pendingSnapshot(source: DataSource, claim: AnalysisClaim): String? = analysisScalar(source, """
    select jsonb_build_array(pending_product_name,pending_product_description,pending_product_image_url,pending_canonical_url,
                            pending_category_id,pending_purpose_id,pending_failure_code,candidate_snapshot_json)::text
    from analysis_jobs where id='${claim.jobId}'
""".trimIndent())

/** Pauses a real external callback, allowing a concurrent DB change without sleeps. */
fun <T> pausedAnalysisCall(operation: (() -> Unit) -> T, invalidate: () -> Unit): T {
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val pool = Executors.newSingleThreadExecutor()
    try {
        val future = pool.submit<T> { operation { entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)) } }
        check(entered.await(10, TimeUnit.SECONDS))
        invalidate()
        resume.countDown()
        return future.get(10, TimeUnit.SECONDS)
    } finally { resume.countDown(); pool.shutdownNow() }
}
