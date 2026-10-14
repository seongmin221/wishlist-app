package app.wishlist.shared.domain

import app.wishlist.shared.model.CategoryCommand

/**
 * B2 custom category input rules, mirrored from the server's `UserTextRules`/`CategoryInputPolicy`.
 * Lengths are Unicode code points (a surrogate pair is 1, CRLF is 2). Only the description may hold
 * line breaks (LF and CRLF; a lone CR is a control character). Field names match the server's
 * `details.fields`. Known gap: the server compares names after NFC; common Kotlin has no NFC, so
 * [normalizedKey] (Fake duplicate check, blank check) skips it.
 */
object CategoryInputRules {
    // Not NAME_MAX & co.: those are POSIX <limits.h> macros and would break the ObjC header.
    const val NAME_LIMIT = 40
    const val DESCRIPTION_LIMIT = 200
    const val EXAMPLE_COUNT_LIMIT = 5
    const val EXAMPLE_LIMIT = 60

    fun codePoints(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    /** Server field names of every invalid input: `name`, `description`, `examples`. */
    fun validate(command: CategoryCommand): Set<String> = buildSet {
        if (!valid(command.name, NAME_LIMIT) || isBlank(command.name)) add("name")
        command.description?.let { if (!valid(it, DESCRIPTION_LIMIT, multiline = true)) add("description") }
        if (command.examples.size > EXAMPLE_COUNT_LIMIT || command.examples.any { !valid(it, EXAMPLE_LIMIT) || isBlank(it) }) add("examples")
    }

    /** True when [text] holds a character the server rejects (length is not checked here). */
    fun hasForbidden(text: String, allowLineBreaks: Boolean): Boolean {
        val inspected = if (allowLineBreaks) text.replace("\r\n", "\n") else text
        var i = 0
        while (i < inspected.length) {
            val c = inspected[i]
            when {
                c.isHighSurrogate() && i + 1 < inspected.length && inspected[i + 1].isLowSurrogate() -> { i += 2; continue }
                c.isSurrogate() -> return true
                isControl(c) && !(allowLineBreaks && c == '\n') -> return true
                c == '​' || c == '﻿' || c in '‪'..'‮' || c in '⁦'..'⁩' -> return true
            }
            i++
        }
        return false
    }

    /** Whitespace trim/collapse + lowercase (the server also applies NFC first). */
    fun normalizedKey(text: String): String {
        val out = StringBuilder()
        var space = false
        for (c in text) {
            if (c.isWhitespace()) {
                if (out.isNotEmpty()) space = true
            } else {
                if (space) out.append(' ')
                out.append(c)
                space = false
            }
        }
        return out.toString().lowercase()
    }

    fun isBlank(text: String): Boolean = normalizedKey(text).isEmpty()

    private fun valid(text: String, maximum: Int, multiline: Boolean = false): Boolean =
        codePoints(text) <= maximum && !hasForbidden(text, multiline)

    /** Unicode Cc: U+0000–U+001F and U+007F–U+009F. */
    private fun isControl(c: Char): Boolean = c <= '\u001F' || c in '\u007F'..'\u009F'
}
