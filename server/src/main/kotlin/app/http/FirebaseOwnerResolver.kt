package app.http

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID

class FirebaseOwnerResolver(
    private val projectId: String,
    private val verifyUid: (String) -> String?,
) {
    suspend fun resolve(call: ApplicationCall): UUID? {
        val authorization = call.request.headers["Authorization"] ?: return null
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
        val token = authorization.substringAfter(' ').trim().takeIf { it.isNotEmpty() } ?: return null
        val uid = withContext(Dispatchers.IO) { try { verifyUid(token) } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            null
        } } ?: return null
        return UUID.nameUUIDFromBytes("firebase:$projectId:$uid".toByteArray(StandardCharsets.UTF_8))
    }
}
