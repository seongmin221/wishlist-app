package app.http

import app.apiRoutes
import app.testutil.withAnalysisDatabase
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiRouteExposureTest {
    @Test fun `public api routes do not include any internal endpoint`() = withAnalysisDatabase { source ->
        testApplication {
            application { routing { apiRoutes(source, { _ -> }) { UUID.fromString("00000000-0000-0000-0000-000000000001") } } }
            for (path in listOf("/internal/worker/general", "/internal/worker/browser", "/internal/maintenance/run")) {
                assertEquals(HttpStatusCode.NotFound, client.post(path).status, path)
            }
        }
    }
}
