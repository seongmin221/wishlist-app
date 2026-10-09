package app.wishlist.shared.data.fake

import kotlin.time.Instant
import app.wishlist.shared.core.hasValidUtf8Encoding

/** Creation-time checks only: Fake never performs DNS or extraction's network safety checks. */
internal fun isValidFakeSourceUrl(sourceUrl: String): Boolean {
    if (sourceUrl.length > 2048 || !hasValidUtf8Encoding(sourceUrl)) return false
    val host = fakeCreateUrlHost(sourceUrl)?.lowercase()?.removeSurrounding("[", "]") ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return false
    val octets = host.split('.')
    if (octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }) {
        return octets[0].toInt() != 127 && !octets.all { it.toInt() == 0 }
    }
    return ':' !in host || !isLocalFakeIpv6(host)
}

/** Native URL parsers differ; platform implementations keep raw URI validation explicit. */
internal expect fun fakeCreateUrlHost(sourceUrl: String): String?
internal expect fun isLocalFakeIpv6(host: String): Boolean

internal fun isValidFakeClientCreatedAt(at: Instant?): Boolean =
    at == null || at.epochSeconds in -62_135_596_800L until 253_402_300_800L

internal fun normalizeFakeClientCreatedAt(at: Instant?): Instant? = at?.let {
    Instant.fromEpochSeconds(it.epochSeconds, (it.nanosecondsOfSecond / 1000) * 1000L)
}
