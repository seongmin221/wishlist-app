package app.extraction

import java.text.Normalizer

/** Seller-independent product identity read from structured data; never guessed from names. */
object ProductIdentifiers {
    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)
    private val MPN_SEPARATORS = Regex("""[\s\-_./]""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]""")

    /** One GTIN-14 when every value given is valid and they agree; any invalid value, none, or disagreement means null. */
    fun gtin(raw: List<String>): String? = raw.map { normalizeGtin(it) ?: return null }.distinct().singleOrNull()

    /** `brand|mpn` for a usable brand and model number; either missing means no key. */
    fun mpnKey(brand: String?, mpn: String?): String? {
        val brandKey = brand?.let { nfkc(it).lowercase().replace(NON_ALPHANUMERIC, "") }?.takeIf { it.isNotEmpty() } ?: return null
        val model = mpn?.let { nfkc(it).uppercase().replace(MPN_SEPARATORS, "") }?.takeIf { it.length in 3..64 } ?: return null
        return "$brandKey|$model"
    }

    private fun normalizeGtin(value: String): String? {
        val digits = value.replace(Regex("""[\s-]"""), "")
        if (digits.length !in GTIN_LENGTHS || !digits.all { it in '0'..'9' } || digits.all { it == '0' }) return null
        val body = digits.dropLast(1)
        // GS1 mod-10: weights 3,1,3,... from the digit nearest the check digit.
        val sum = body.reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return if ((10 - sum % 10) % 10 == digits.last() - '0') digits.padStart(14, '0') else null
    }

    private fun nfkc(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
}
