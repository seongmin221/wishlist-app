package app.wishlist.shared.data.remote

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// Ktor 3.4.3's default Darwin delegate answers every redirect callback with completionHandler(null),
// so redirects are not followed; no custom delegate is added.
internal actual fun platformHttpEngine(): HttpClientEngine = Darwin.create()
