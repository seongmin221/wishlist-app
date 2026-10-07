package app.category

import kotlin.test.*

class CategoryInputPolicyTest {
    @Test fun `documented Unicode separators filler and direction marks remain allowed`() {
        for(value in listOf("a\u2028b","a\u2029b","\u3164","\u2800","\u200e","\u200f","\u061c")) {
            assertEquals(emptySet(), CategoryInputPolicy.validate(CategoryInput(value, value, listOf(value))))
        }
    }

    @Test fun `unicode whitespace and case normalize only the comparison key`() {
        val input = CategoryInput("  My\u00a0  Desk  ", null, emptyList())
        assertEquals(emptySet(), CategoryInputPolicy.validate(input))
        assertEquals("my desk", CategoryInputPolicy.normalizedName(input.name))
        assertEquals("  My\u00a0  Desk  ", input.name)
        assertEquals("i", CategoryInputPolicy.normalizedName("I"))
    }

    @Test fun `limits count unicode code points including supplementary characters`() {
        val boundary = CategoryInput("😀".repeat(40), "설".repeat(200), List(5) { "😀".repeat(60) })
        assertEquals(emptySet(), CategoryInputPolicy.validate(boundary))
        assertEquals(setOf("name"), CategoryInputPolicy.validate(boundary.copy(name = "😀".repeat(41))))
        assertEquals(setOf("description"), CategoryInputPolicy.validate(boundary.copy(description = "설".repeat(201))))
        assertEquals(setOf("examples"), CategoryInputPolicy.validate(boundary.copy(examples = List(6) { "예" })))
        assertEquals(setOf("examples"), CategoryInputPolicy.validate(boundary.copy(examples = listOf("😀".repeat(61)))))
    }

    @Test fun `blank names controls format characters and malformed unicode are invalid`() {
        for (name in listOf("", " \u00a0\u2003 ", "a\n", "a\u0000", "a\u007f", "a\u200b", "a\uD800")) {
            assertEquals(setOf("name"), CategoryInputPolicy.validate(CategoryInput(name, null, emptyList())), name)
        }
        assertEquals(setOf("description", "examples"),
            CategoryInputPolicy.validate(CategoryInput("desk", "bad\u0000", listOf("bad\uDC00"))))
    }

    @Test fun `optional empty text is allowed without changing original text`() {
        assertEquals(emptySet(), CategoryInputPolicy.validate(CategoryInput("책상", "", listOf(" 예시 "))))
    }

    @Test fun `optional examples reject present blank strings without rewriting them`() {
        for(value in listOf("", "  ", "\u00a0\u2003")) assertEquals(setOf("examples"),CategoryInputPolicy.validate(CategoryInput("desk",null,listOf(value))))
        assertEquals(emptySet(),CategoryInputPolicy.validate(CategoryInput("desk",null,emptyList())))
    }

    @Test fun `visible joined unicode text remains valid`() {
        for (name in listOf("👩‍💻", "می\u200cخواهم", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F")) {
            assertEquals(emptySet(), CategoryInputPolicy.validate(CategoryInput(name, null, emptyList())), name)
        }
    }

    @Test fun `description allows LF and CRLF while names examples and other controls stay strict`() {
        assertEquals(emptySet(),CategoryInputPolicy.validate(CategoryInput("desk","one\ntwo\r\nthree",listOf("keyboard"))))
        assertEquals(setOf("description"),CategoryInputPolicy.validate(CategoryInput("desk","one\rtwo",emptyList())))
        assertEquals(setOf("name","examples"),CategoryInputPolicy.validate(CategoryInput("one\ntwo",null,listOf("one\ntwo"))))
    }

    @Test fun `comparison keys normalize canonically equivalent Hangul without changing display text`() {
        assertEquals("책상",CategoryInputPolicy.normalizedName("\u110E\u1162\u11A8\u1109\u1161\u11BC"))
    }
}
