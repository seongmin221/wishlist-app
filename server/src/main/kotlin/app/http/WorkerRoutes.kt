package app.http

import app.analysis.GeneralWorkerService
import app.analysis.WorkerDisposition
import app.browser.BrowserWorkerService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Test convenience registering both lanes; runtime roles register only their own lane. */
fun Route.workerRoutes(worker: GeneralWorkerService, browser: BrowserWorkerService? = null) =
    workerRoutes(worker::runGeneral, browser?.let { it::runBrowser })

fun Route.workerRoutes(runGeneral: (UUID, Int) -> WorkerDisposition, runBrowser: ((UUID, Int) -> WorkerDisposition)? = null) {
    generalWorkerRoute(runGeneral)
    if (runBrowser != null) browserWorkerRoute(runBrowser)
}

fun Route.generalWorkerRoute(run: (UUID, Int) -> WorkerDisposition) = workerRoute("/internal/worker/general", run)

fun Route.browserWorkerRoute(run: (UUID, Int) -> WorkerDisposition) = workerRoute("/internal/worker/browser", run)

private fun Route.workerRoute(path: String, run: (UUID, Int) -> WorkerDisposition) {
    post(path) {
        val request = try {
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            UUID.fromString(json.getValue("jobId").jsonPrimitive.content) to json.getValue("generation").jsonPrimitive.int
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            null
        } ?: return@post call.respondText("invalid task", ContentType.Text.Plain, HttpStatusCode.BadRequest)

        when (withContext(Dispatchers.IO) { run(request.first, request.second) }) {
            WorkerDisposition.ACKNOWLEDGE -> call.respond(HttpStatusCode.NoContent)
            // Only executions with no durable record ask Cloud Tasks to deliver again.
            WorkerDisposition.RETRY -> call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }
}
