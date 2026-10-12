package app.http

import app.DatabaseFactory
import app.analysis.GeneralWorkerService
import app.analysis.ProcessingOutcome
import app.browser.BrowserWorkerService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import app.testutil.PostgresTestContainer
import app.testutil.*
import app.analysis.AnalysisLane
import app.analysis.AnalysisClaim

class WorkerRoutesTest {
    @Test fun `blocking general and browser services run outside the route executor`() {
        for (lane in listOf("general", "browser")) {
            assertBlockingRouteIo({ block -> workerRoutes({ _, _ -> block(); app.analysis.WorkerDisposition.ACKNOWLEDGE }, { _, _ -> block(); app.analysis.WorkerDisposition.ACKNOWLEDGE }) }, {
                post("/internal/worker/$lane") { setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""") }
            })
        }
    }

    @Test fun `general and browser cancellation propagate to request pipeline`() {
        for (lane in listOf("general", "browser")) {
            assertRouteCancellation({ block -> workerRoutes({ _, _ -> block(); app.analysis.WorkerDisposition.ACKNOWLEDGE }, { _, _ -> block(); app.analysis.WorkerDisposition.ACKNOWLEDGE }) }, {
                post("/internal/worker/$lane") { setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""") }
            })
        }
    }

    @Test fun `durable current faults stale faults and finished work are all acknowledged`() = withAnalysisDatabase { source ->
        var mode = "fault"
        val process: (AnalysisClaim) -> ProcessingOutcome = { claim ->
            when (mode) {
                "fault" -> error("temporary infrastructure failure")
                "stale_fault" -> {
                    analysisSql(source, "update wishlist_items set version=version+1 where id='${claim.itemId}'")
                    error("late infrastructure failure")
                }
                else -> { seedFinishResult(source, claim); ProcessingOutcome.Complete }
            }
        }
        val worker = GeneralWorkerService(source, process)
        val browser = BrowserWorkerService(source, { app.extraction.Metadata("render", null, null, "https://example.com/item") }, { claim, _ -> process(claim) })
        testApplication {
            application { routing { workerRoutes(worker, browser) } }
            for (lane in AnalysisLane.entries) for (case in listOf("fault", "stale_fault", "complete")) {
                mode = case
                val job = newFinishJob(source, lane)
                val response = client.post("/internal/worker/${lane.name.lowercase()}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"jobId":"${job.jobId}","generation":1}""")
                }
                assertEquals(HttpStatusCode.NoContent, response.status, "$lane $case")
                if (case == "fault") assertEquals("${lane.name}_PENDING", analysisScalar(source, "select stage from analysis_jobs where id='${job.jobId}'"))
            }
        }
    }

    @Test
    fun `stale task is acknowledged with no content`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val worker = GeneralWorkerService(DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)) { ProcessingOutcome.Retryable }
            testApplication {
                application { routing { workerRoutes(worker) } }
                val response = client.post("/internal/worker/general") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""")
                }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
        }
    }

    @Test
    fun `stale browser task is acknowledged with no content`() {
        PostgresTestContainer().use { database ->
            database.start()
            DatabaseFactory.migrate(database.jdbcUrl, database.username, database.password)
            val source = DatabaseFactory.dataSource(database.jdbcUrl, database.username, database.password)
            val worker = GeneralWorkerService(source) { ProcessingOutcome.Retryable }
            val browser = BrowserWorkerService(source, { error("must not render") }, { _, _ -> error("must not classify") })
            testApplication {
                application { routing { workerRoutes(worker, browser) } }
                val response = client.post("/internal/worker/browser") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"jobId":"00000000-0000-0000-0000-000000000001","generation":1}""")
                }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
        }
    }
}
