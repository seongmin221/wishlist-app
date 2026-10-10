package app.wishlist.android.feature.web

import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute
import java.util.Base64

/**
 * The in-app original-link web view (FWebView). Built only through [of], so every instance holds an
 * `http`/`https` URL with a host; restoring a saved stack re-validates through [decodeToken]. Account-scoped:
 * it closes with the detail routes when the account is left.
 */
internal data class WebViewRoute private constructor(val url: String) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true

    companion object {
        fun of(url: String): WebViewRoute? = if (WebUrl.isWeb(url)) WebViewRoute(url) else null

        /** UTF-8 base64url without padding, so `/ ? # % & =` never reach the codec's token parsing. */
        fun encodeToken(url: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))

        /** null on bad base64 or a URL [of] rejects. */
        fun decodeToken(token: String): WebViewRoute? {
            if (token.isEmpty()) return null
            val bytes = try {
                Base64.getUrlDecoder().decode(token)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return of(String(bytes, Charsets.UTF_8))
        }
    }
}

/** `http`/`https` URL with a non-empty host; no whitespace, control characters or backslashes. */
internal object WebUrl {
    fun isWeb(url: String): Boolean {
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val scheme = url.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return false
        val rest = url.substring(colon + 1)
        if (!rest.startsWith("//")) return false
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.isNotEmpty() && host != "[]"
    }
}
