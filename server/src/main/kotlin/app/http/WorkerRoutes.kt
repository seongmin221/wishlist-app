package app.http

import app.analysis.GeneralWorkerService
import app.analysis.WorkerDisposition
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

fun Route.workerRoutes(worker: GeneralWorkerService) {
    post("/internal/worker/general") {
        val request = runCatching {
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            UUID.fromString(json.getValue("jobId").jsonPrimitive.content) to json.getValue("generation").jsonPrimitive.int
        }.getOrNull() ?: return@post call.respondText("invalid task", ContentType.Text.Plain, HttpStatusCode.BadRequest)

        when (worker.runGeneral(request.first, request.second)) {
            WorkerDisposition.ACKNOWLEDGE -> call.respond(HttpStatusCode.NoContent)
            WorkerDisposition.RETRY -> call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }
}
