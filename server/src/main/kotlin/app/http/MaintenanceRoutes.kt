package app.http

import app.maintenance.MaintenanceReport
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private Scheduler endpoint. `run` returns null when the runtime is stopping and refuses new work. */
fun Route.maintenanceRoutes(run: () -> MaintenanceReport?) {
    post("/internal/maintenance/run") {
        val report = withContext(Dispatchers.IO) { run() } ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        // A failed step surfaces as 500 so monitoring sees it; the report still says what the other steps did.
        val status = if (report.failedSteps.isEmpty()) HttpStatusCode.OK else HttpStatusCode.InternalServerError
        call.respondText(ApiJson.encodeToString(MaintenanceReport.serializer(), report), ContentType.Application.Json, status)
    }
}
