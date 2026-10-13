package app.http

import app.media.LocalMediaStorage
import app.media.LocalUploadResult
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** Stand-in for direct bucket uploads; registered only when the runtime uses [LocalMediaStorage]. */
fun Route.localMediaRoutes(storage: LocalMediaStorage) {
    put("/local/media-objects/{key...}") {
        val key = call.objectKey()
        val query = call.singleQuery()
        val result = withContext(Dispatchers.IO) {
            storage.acceptUpload(key, query, { name -> call.request.headers[name] }, call.receiveChannel().toInputStream())
        }
        call.respond(when (result) {
            LocalUploadResult.STORED -> HttpStatusCode.OK
            LocalUploadResult.FORBIDDEN -> HttpStatusCode.Forbidden
            LocalUploadResult.EXISTS -> HttpStatusCode.PreconditionFailed
            LocalUploadResult.TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        })
    }
    get("/local/media-objects/{key...}") {
        val file = storage.openForRead(call.objectKey(), call.singleQuery()) ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respondBytes(withContext(Dispatchers.IO) { Files.readAllBytes(file) }, ContentType.Image.JPEG)
    }
}

private fun ApplicationCall.objectKey() = parameters.getAll("key").orEmpty().joinToString("/")
private fun ApplicationCall.singleQuery(): Map<String, String> =
    request.queryParameters.entries().filter { it.value.size == 1 }.associate { it.key to it.value.single() }
