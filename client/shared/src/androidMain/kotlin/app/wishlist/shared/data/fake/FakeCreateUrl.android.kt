package app.wishlist.shared.data.fake

import java.net.InetAddress
import java.net.URI

internal actual fun fakeCreateUrlHost(sourceUrl: String): String? = runCatching {
    URI(sourceUrl).takeIf { it.scheme?.lowercase() in setOf("http", "https") }?.host?.takeIf { it.isNotBlank() }
}.getOrNull()

internal actual fun isLocalFakeIpv6(host: String): Boolean = runCatching {
    // A colon guarantees an IPv6 literal: this does not resolve a DNS name.
    InetAddress.getByName(host).let { it.isLoopbackAddress || it.isAnyLocalAddress }
}.getOrDefault(true)
