package app.wishlist.shared.domain

import app.wishlist.shared.model.DecimalAmount

private val currencyCode = Regex("[A-Z]{3}")

/** Exact, locale-independent price display; absent or malformed inputs hide the price. */
fun formatPrice(amount: DecimalAmount?, currency: String?): String? {
    if (amount == null || currency == null) return null
    val code = currency.trim().uppercase()
    if (!currencyCode.matches(code)) return null

    val scale = CurrencyMinorUnits.forCode(code)
    val canonical = amount.canonical
    val magnitude = canonical.removePrefix("-")
    val sourceWhole = magnitude.substringBefore('.')
    val sourceFraction = magnitude.substringAfter('.', "")
    var digits = sourceWhole + sourceFraction.take(scale).padEnd(scale, '0')
    // HALF_UP rounds the magnitude away from zero at a tie, including negative amounts.
    if (sourceFraction.length > scale && sourceFraction[scale] >= '5') {
        digits = incrementDigits(digits)
    }
    val whole = digits.dropLast(scale).trimStart('0').ifEmpty { "0" }
    val fraction = if (scale == 0) "" else digits.takeLast(scale)
    val hasFraction = fraction.any { it != '0' }
    val sign = if (canonical.startsWith('-') && (whole != "0" || hasFraction)) "-" else ""
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    val tail = if (hasFraction) ".$fraction" else ""
    return "$code $sign$grouped$tail"
}

/** ObjC/Swift callable facade independent of Flow/SKIE; parsing never throws to Swift. */
class PriceFormatter {
    fun formatOrNull(amount: String?, currency: String?): String? =
        formatPrice(amount?.let(DecimalAmount::parseOrNull), currency)
}

private fun incrementDigits(digits: String): String {
    val result = digits.toCharArray()
    for (index in result.lastIndex downTo 0) {
        if (result[index] != '9') {
            result[index] = result[index] + 1
            return result.concatToString()
        }
        result[index] = '0'
    }
    return "1" + result.concatToString()
}
