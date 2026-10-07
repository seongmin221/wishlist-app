package app.wishlist.shared.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create {
    config {
        // Redirects are a typed INVALID_RESPONSE, never followed (the bearer must not travel).
        followRedirects(false)
        followSslRedirects(false)
        // Native recovery of dead keep-alive connections stays on; it resends the same request/key.
        retryOnConnectionFailure(true)
    }
}
