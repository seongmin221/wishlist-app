package app.wishlist.shared.domain

import app.wishlist.shared.model.CategoryCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B2 input contract: lengths are Unicode code points, forbidden characters per field. */
class CategoryInputRulesTest {
    private fun cmd(name: String = "오디오", description: String? = null, examples: List<String> = emptyList(), parentId: String = "G003") =
        CategoryCommand(parentId, name, description, examples)

    private fun fields(c: CategoryCommand) = CategoryInputRules.validate(c)

    @Test fun codePointCounts() {
        assertEquals(2, CategoryInputRules.codePoints("가나"))
        assertEquals(1, CategoryInputRules.codePoints("😀")) // one surrogate pair
        assertEquals(7, CategoryInputRules.codePoints("👨‍👩‍👧‍👦")) // 4 people + 3 ZWJ
        assertEquals(2, CategoryInputRules.codePoints("\r\n"))
        assertEquals(3, CategoryInputRules.codePoints("각")) // NFD 각
    }

    @Test fun nameLength() {
        assertEquals(emptySet(), fields(cmd(name = "가".repeat(40))))
        assertEquals(setOf("name"), fields(cmd(name = "가".repeat(41))))
        assertEquals(emptySet(), fields(cmd(name = "😀".repeat(40)))) // 80 UTF-16 units, 40 code points
        assertEquals(setOf("name"), fields(cmd(name = "   ")))
        assertEquals(setOf("name"), fields(cmd(name = "")))
    }

    @Test fun descriptionAndExamplesLength() {
        assertEquals(emptySet(), fields(cmd(description = "a".repeat(200))))
        assertEquals(setOf("description"), fields(cmd(description = "a".repeat(201))))
        assertEquals(setOf("description"), fields(cmd(description = "a".repeat(199) + "\r\n"))) // CRLF counts 2
        assertEquals(emptySet(), fields(cmd(examples = List(5) { "예시$it" })))
        assertEquals(setOf("examples"), fields(cmd(examples = List(6) { "예시$it" })))
        assertEquals(setOf("examples"), fields(cmd(examples = listOf("a".repeat(61)))))
        assertEquals(setOf("examples"), fields(cmd(examples = listOf(" "))))
        assertEquals(emptySet(), fields(cmd(examples = emptyList())))
    }

    @Test fun lineBreaks() {
        assertEquals(emptySet(), fields(cmd(description = "업무 공간\n주변 장비\r\n끝")))
        assertEquals(setOf("description"), fields(cmd(description = "단독\r")))
        assertEquals(setOf("name"), fields(cmd(name = "두\n줄")))
        assertEquals(setOf("name"), fields(cmd(name = "두\r\n줄")))
        assertEquals(setOf("examples"), fields(cmd(examples = listOf("두\n줄"))))
    }

    @Test fun forbiddenCharacters() {
        for (bad in listOf("\u0000", "\u0007", "\u007F", "\u0085", "​", "﻿", "‪", "‮", "⁦", "⁩", "\uD800", "a\uDC00")) {
            assertEquals(setOf("name"), fields(cmd(name = "x${bad}y")), bad.map { it.code.toString(16) }.toString())
            assertTrue(CategoryInputRules.hasForbidden("x${bad}y", allowLineBreaks = true))
        }
        assertEquals(setOf("description"), fields(cmd(description = "탭\t문자")))
    }

    @Test fun allowedUnusualCharacters() {
        for (ok in listOf(" ", " ", "ㅤ", "⠀", "‎", "‏", "؜", "👨‍👩‍👧", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "ক্‌ষ")) {
            assertEquals(emptySet(), fields(cmd(name = "a${ok}b")), ok)
            assertFalse(CategoryInputRules.hasForbidden(ok, allowLineBreaks = false), ok)
        }
    }

    @Test fun severalFieldsAtOnce() {
        assertEquals(setOf("name", "description", "examples"),
            fields(cmd(name = "", description = "a".repeat(201), examples = listOf(""))))
    }
}
