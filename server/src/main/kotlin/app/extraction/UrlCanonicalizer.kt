package app.extraction

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Canonical URL policy: keep product-identifying query, drop fragments and approved tracking parameters. */
object UrlCanonicalizer {
    val TRACKING_PARAMETERS = setOf("fbclid", "gclid", "msclkid", "igshid", "mc_cid", "mc_eid")
    private const val TRACKING_PREFIX = "utm_"

    fun normalize(url: String): String = url.toHttpUrlOrNull()?.let(::normalize) ?: url

    /** Adopts a page-declared canonical only on the final URL's registrable domain; otherwise the normalized final URL. */
    fun choose(declared: String?, finalUrl: String): String {
        val final = finalUrl.toHttpUrlOrNull() ?: return finalUrl
        // Strict RFC 3986 syntax first: lenient resolution would turn arbitrary text into a same-site relative path.
        val candidate = declared?.trim()?.takeIf { it.isNotEmpty() && runCatching { URI(it) }.isSuccess }?.let(final::resolve)
        return normalize(if (candidate != null && trusted(candidate, final)) candidate else final)
    }

    private fun trusted(candidate: HttpUrl, final: HttpUrl): Boolean {
        val domain = candidate.topPrivateDomain() ?: return false   // null for IP literals and public suffixes
        // An https page never adopts an http canonical; the reverse upgrade is fine.
        if (final.isHttps && !candidate.isHttps) return false
        return candidate.username.isEmpty() && candidate.password.isEmpty() &&
            candidate.port == HttpUrl.defaultPort(candidate.scheme) && domain == final.topPrivateDomain()
    }

    private fun normalize(url: HttpUrl): String {
        val builder = url.newBuilder().fragment(null)
        url.queryParameterNames.filter { it in TRACKING_PARAMETERS || it.startsWith(TRACKING_PREFIX) }
            .forEach(builder::removeAllQueryParameters)
        val cleaned = builder.build()
        return (if (cleaned.querySize == 0) cleaned.newBuilder().query(null).build() else cleaned).toString()
    }
}
