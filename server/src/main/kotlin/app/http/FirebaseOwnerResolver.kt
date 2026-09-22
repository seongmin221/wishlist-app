package app.http

import io.ktor.server.application.ApplicationCall
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
        val uid = withContext(Dispatchers.IO) { runCatching { verifyUid(token) }.getOrNull() } ?: return null
        return UUID.nameUUIDFromBytes("firebase:$projectId:$uid".toByteArray(StandardCharsets.UTF_8))
    }
}
