package app.wishlist.shared.domain

/** What a shared text yields: its first http(s) link, or why there is none. */
sealed interface ParsedShare {
    data class Link(val url: String) : ParsedShare
    data object NoLink : ParsedShare
    data object TooLong : ParsedShare
}

/**
 * Extracts the first http(s) link from shared text (C3-D4). Rule shared with the iOS extension's
 * Swift extractor (same test vectors): first match of `https?://[^\s<>"'　]+` (case-insensitive),
 * then trailing `.,;:!?` and unpaired closing brackets/quotes are trimmed repeatedly; an empty host
 * is no link; more than [MAX_URL_LENGTH] UTF-16 units is too long. Case and percent-encoding are
 * kept as shared (normalization is the server's job).
 */
object ShareTextParser {
    const val MAX_URL_LENGTH = 2048

    private val link = Regex("https?://[^\\s<>\"'　]+", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?"
    private val openerOf = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<', '」' to '「', '』' to '『')
    private const val QUOTES = "'\""

    fun parse(text: String?): ParsedShare {
        if (text.isNullOrEmpty()) return ParsedShare.NoLink
        val match = link.find(text) ?: return ParsedShare.NoLink
        val url = trimTrailing(match.value)
        val host = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        return when {
            host.isEmpty() -> ParsedShare.NoLink
            url.length > MAX_URL_LENGTH -> ParsedShare.TooLong
            else -> ParsedShare.Link(url)
        }
    }

    private fun trimTrailing(candidate: String): String {
        var url = candidate
        while (url.isNotEmpty() && isTrimmable(url)) url = url.dropLast(1)
        return url
    }

    private fun isTrimmable(url: String): Boolean {
        val last = url.last()
        val opener = openerOf[last]
        return when {
            last in TRAILING_PUNCTUATION -> true
            opener != null -> url.count { it == opener } < url.count { it == last }
            last in QUOTES -> url.count { it == last } % 2 == 1
            else -> false
        }
    }
}
