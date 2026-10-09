package app.text

import java.text.Normalizer
import java.util.Locale

/** Shared rules for user-entered names and descriptions. Stored display text is never rewritten. */
object UserTextRules {
    fun valid(text: String, maximum: Int, multiline: Boolean = false): Boolean {
        if (text.codePointCount(0, text.length) > maximum) return false
        val inspected = if (multiline) text.replace("\r\n", "\n") else text
        return inspected.codePoints().allMatch { point ->
            point !in 0xD800..0xDFFF && (Character.getType(point) != Character.CONTROL.toInt() || multiline && point == 10) &&
                point != 0x200B && point != 0xFEFF && point !in 0x202A..0x202E && point !in 0x2066..0x2069
        }
    }

    /** NFC, Unicode whitespace trim/collapse and Locale.ROOT lowercase; used only for comparison keys. */
    fun normalizedKey(text: String): String {
        val normalized = StringBuilder()
        var space = false
        Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().forEach { point ->
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if (normalized.isNotEmpty()) space = true
            } else {
                if (space) normalized.append(' ')
                normalized.appendCodePoint(point)
                space = false
            }
        }
        return normalized.toString().lowercase(Locale.ROOT)
    }

    fun isBlank(text: String): Boolean = normalizedKey(text).isEmpty()

    /** Keeps at most [maximum] Unicode code points so surrogate pairs are never split. */
    fun truncate(text: String, maximum: Int): String =
        text.codePoints().limit(maximum.toLong()).toArray().let { String(it, 0, it.size) }
}
