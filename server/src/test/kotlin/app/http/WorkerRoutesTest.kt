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
import org.testcontainers.containers.PostgreSQLContainer

class WorkerRoutesTest {
    @Test
    fun `stale task is acknowledged with no content`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
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
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { database ->
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
