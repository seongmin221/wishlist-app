package app.http

import app.media.LocalMediaStorage
import app.media.LocalRead
import app.media.LocalUploadResult
import app.media.MediaStorageException
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stand-in for direct bucket uploads; registered only when the runtime uses [LocalMediaStorage]. */
fun Route.localMediaRoutes(storage: LocalMediaStorage) {
    put("/local/media-objects/{key...}") {
        val key = call.objectKey()
        val query = call.singleQuery()
        val result = try {
            withContext(Dispatchers.IO) { storage.acceptUpload(key, query, { name -> call.request.headers[name] }, call.receiveChannel().toInputStream()) }
        } catch (_: MediaStorageException) {
            return@put call.respond(HttpStatusCode.ServiceUnavailable)
        }
        call.respond(when (result) {
            LocalUploadResult.STORED -> HttpStatusCode.OK
            LocalUploadResult.FORBIDDEN -> HttpStatusCode.Forbidden
            LocalUploadResult.EXISTS -> HttpStatusCode.PreconditionFailed
            LocalUploadResult.TOO_LARGE -> HttpStatusCode.PayloadTooLarge
            LocalUploadResult.EMPTY -> HttpStatusCode.BadRequest
        })
    }
    get("/local/media-objects/{key...}") {
        val read = try {
            withContext(Dispatchers.IO) { storage.readSigned(call.objectKey(), call.singleQuery()) }
        } catch (_: MediaStorageException) {
            return@get call.respond(HttpStatusCode.ServiceUnavailable)
        }
        when (read) {
            LocalRead.Forbidden -> call.respond(HttpStatusCode.Forbidden)
            LocalRead.Missing -> call.respond(HttpStatusCode.NotFound)
            is LocalRead.Found -> call.respondBytes(read.bytes, ContentType.Image.JPEG)
        }
    }
}

private fun ApplicationCall.objectKey() = parameters.getAll("key").orEmpty().joinToString("/")
private fun ApplicationCall.singleQuery(): Map<String, String> =
    request.queryParameters.entries().filter { it.value.size == 1 }.associate { it.key to it.value.single() }
