package app.wishlist.shared.model

/** Exact plain decimal value. Parsing is independent of currency and metadata precision. */
class DecimalAmount private constructor(val canonical: String) {
    override fun equals(other: Any?): Boolean = other is DecimalAmount && canonical == other.canonical
    override fun hashCode(): Int = canonical.hashCode()
    override fun toString(): String = canonical

    companion object {
        private val plainDecimal = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")

        /** Returns null for malformed/nonfinite input; never expands exponent notation. */
        fun parseOrNull(value: String): DecimalAmount? {
            if (!plainDecimal.matches(value)) return null
            val negative = value.startsWith('-')
            val magnitude = value.removePrefix("+").removePrefix("-")
            val whole = magnitude.substringBefore('.').trimStart('0').ifEmpty { "0" }
            val fraction = magnitude.substringAfter('.', "").trimEnd('0')
            val sign = if (negative && (whole != "0" || fraction.isNotEmpty())) "-" else ""
            val tail = if (fraction.isEmpty()) "" else ".$fraction"
            return DecimalAmount("$sign$whole$tail")
        }
    }
}
