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
 * then trailing `.,;:!?` and unpaired closing brackets are trimmed repeatedly (one pass: bracket
 * counts are taken once and updated as closers are dropped); an empty host ([hostOf]) is no link;
 * more than [MAX_URL_LENGTH] UTF-16 units is too long. Case and percent-encoding are kept as
 * shared (normalization is the server's job). The pattern already excludes `<>"'`, so neither quotes
 * nor `>` can reach the trimming.
 */
object ShareTextParser {
    const val MAX_URL_LENGTH = 2048

    private val link = Regex("https?://[^\\s<>\"'　]+", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?"
    private val openerOf = mapOf(')' to '(', ']' to '[', '}' to '{', '」' to '「', '』' to '『')

    fun parse(text: String?): ParsedShare {
        if (text.isNullOrEmpty()) return ParsedShare.NoLink
        val match = link.find(text) ?: return ParsedShare.NoLink
        val url = trimTrailing(match.value)
        return when {
            hostOf(url).isEmpty() -> ParsedShare.NoLink
            url.length > MAX_URL_LENGTH -> ParsedShare.TooLong
            else -> ParsedShare.Link(url)
        }
    }

    /**
     * The host of an `scheme://authority...` URL, as both the parser and [DisplayFormat.host] read it:
     * the authority up to `/ ? #`, without userinfo (up to the last `@`) and port. Case is kept.
     */
    internal fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':')
    }

    // Linear: each closer type's counts are taken once, then kept current as characters are dropped.
    private fun trimTrailing(candidate: String): String {
        val count = HashMap<Char, Int>()
        for (c in candidate) if (c in openerOf.keys || c in openerOf.values) count[c] = (count[c] ?: 0) + 1
        var end = candidate.length
        while (end > 0) {
            val last = candidate[end - 1]
            val opener = openerOf[last]
            val trimmable = when {
                last in TRAILING_PUNCTUATION -> true
                opener != null -> (count[opener] ?: 0) < (count[last] ?: 0)
                else -> false
            }
            if (!trimmable) break
            if (opener != null) count[last] = count.getValue(last) - 1
            end--
        }
        return candidate.substring(0, end)
    }
}
