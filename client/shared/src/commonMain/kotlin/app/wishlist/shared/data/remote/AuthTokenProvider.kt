package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.SessionSnapshot

/**
 * Kotlin-internal token source for [AuthenticatedTransport]. Implementations must not cache:
 * the platform SDK owns token lifetime, and [forceRefresh] asks it for a fresh one.
 */
internal interface AuthTokenProvider {
    suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String>
}
