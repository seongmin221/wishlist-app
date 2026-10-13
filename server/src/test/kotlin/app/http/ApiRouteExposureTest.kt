package app.http

import app.apiRoutes
import app.mediaObjectRoutes
import app.testutil.withAnalysisDatabase
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiRouteExposureTest {
    @Test fun `public api routes do not include any internal endpoint`() = withAnalysisDatabase { source ->
        testApplication {
            application { routing { apiRoutes(source, { _ -> }, app.testutil.mediaWiring(source)) { UUID.fromString("00000000-0000-0000-0000-000000000001") } } }
            for (path in listOf("/internal/worker/general", "/internal/worker/browser", "/internal/maintenance/run")) {
                assertEquals(HttpStatusCode.NotFound, client.post(path).status, path)
            }
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/media/upload-intents").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/v1/media/not-an-id/completion").status)
        }
    }

    @Test fun `local media object routes exist only for the local storage configuration`() {
        val storage = app.media.LocalMediaStorage(java.nio.file.Files.createTempDirectory("exposure"), "http://localhost:8080")
        for ((config, expected) in listOf(
            app.MediaStorageConfig.Local("build/local-media", "http://localhost:8080") to HttpStatusCode.Forbidden,
            app.MediaStorageConfig.Gcs("bucket") to HttpStatusCode.NotFound,
        )) testApplication {
            application { routing { mediaObjectRoutes(config, storage) } }
            assertEquals(expected, client.put("/local/media-objects/media/${UUID.randomUUID()}/${UUID.randomUUID()}.jpg").status, config.toString())
        }
        testApplication {
            application { routing { mediaObjectRoutes(app.MediaStorageConfig.Local("d", "http://localhost"), app.testutil.InMemoryMediaStorage()) } }
            assertEquals(HttpStatusCode.NotFound, client.put("/local/media-objects/media/x.jpg").status)
        }
    }

    @Test fun `worker and maintenance routes do not expose media`() {
        testApplication {
            application { routing {
                generalWorkerRoute { _, _ -> app.analysis.WorkerDisposition.ACKNOWLEDGE }
                maintenanceRoutes { null }
            } }
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/media/upload-intents").status)
            assertEquals(HttpStatusCode.NotFound, client.put("/local/media-objects/media/a.jpg").status)
        }
    }
}
