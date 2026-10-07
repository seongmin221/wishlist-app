package app.wishlist.shared.data.remote

import io.ktor.client.engine.HttpClientEngine

/**
 * The production engine. Android: OkHttp with redirects disabled; iOS: Darwin whose default
 * delegate refuses redirects. Timeout support differs per engine; see docs/architecture/client/kmp.md.
 */
internal expect fun platformHttpEngine(): HttpClientEngine
