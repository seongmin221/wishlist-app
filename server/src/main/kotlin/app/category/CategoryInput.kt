package app.category

import java.util.Locale

data class CategoryInput(val name: String, val description: String?, val examples: List<String>)

object CategoryInputPolicy {
    fun validate(input: CategoryInput): Set<String> = buildSet {
        if (!validText(input.name, 40) || normalizedName(input.name).isEmpty()) add("name")
        if (input.description != null && !validText(input.description, 200)) add("description")
        if (input.examples.size > 5 || input.examples.any { !validText(it, 60) }) add("examples")
    }

    /** Preserve display text; normalize only the unique comparison key. */
    fun normalizedName(name: String): String {
        val normalized = StringBuilder()
        var space = false
        name.codePoints().forEach { point ->
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

    private fun validText(text: String, maximum: Int): Boolean {
        if (text.codePointCount(0, text.length) > maximum) return false
        return text.codePoints().allMatch { point ->
            point !in 0xD800..0xDFFF && Character.getType(point) != Character.CONTROL.toInt() &&
                point != 0x200B && point != 0xFEFF && point !in 0x202A..0x202E && point !in 0x2066..0x2069
        }
    }
}
