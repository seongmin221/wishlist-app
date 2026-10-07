package app.category

import kotlin.test.*

class CategoryInputPolicyTest {
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
        assertEquals(emptySet(), CategoryInputPolicy.validate(CategoryInput("책상", "", listOf("", " 예시 "))))
    }

    @Test fun `visible joined unicode text remains valid`() {
        for (name in listOf("👩‍💻", "می\u200cخواهم", "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F")) {
            assertEquals(emptySet(), CategoryInputPolicy.validate(CategoryInput(name, null, emptyList())), name)
        }
    }
}
