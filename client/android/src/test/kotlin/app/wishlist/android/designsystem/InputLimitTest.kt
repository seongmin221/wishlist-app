package app.wishlist.android.designsystem

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class InputLimitTest {
    @Test fun pasteClipsInsteadOfRejectingWholeEdit() {
        val state = TextFieldState("가나다라", TextRange(4))
        enforceCommittedInputLimit(state, 3)
        assertEquals("가나다", state.text.toString())
        assertEquals(TextRange(3), state.selection)
    }

    @Test fun extendedGraphemesRemainWhole() {
        val family = "👨‍👩‍👧‍👦"
        val text = "e\u0301${family}🇰🇷가"
        assertEquals("e\u0301${family}🇰🇷", text.substring(0, graphemeLimitEnd(text, 3)))
        val jamo = "각"
        assertEquals(jamo.length, graphemeLimitEnd(jamo + "나", 1))
    }

    @Test fun loweringLimitClipsExistingTextWithoutAnEdit() {
        val state = TextFieldState("가나다", TextRange(1, 3))
        enforceCommittedInputLimit(state, 1)
        assertEquals("가", state.text.toString())
        assertEquals(TextRange(1), state.selection)
    }

    @Test fun zeroLimitAndSelectionAreSafe() {
        val state = TextFieldState("가", TextRange(1))
        enforceCommittedInputLimit(state, 0)
        assertEquals("", state.text.toString())
        assertEquals(TextRange(0), state.selection)
    }

    // C5: category fields count Unicode code points (server contract) but never split a grapheme.
    @Test fun codePointLimitCutsAtGraphemeBoundary() {
        assertEquals(2, codePointLimitEnd("😀😀😀", 1)) // a surrogate pair is one code point
        val family = "👨‍👩‍👧‍👦" // 7 code points, one grapheme
        assertEquals(2, codePointLimitEnd("가나$family", 8)) // 2 + 7 > 8: the whole family goes
        assertEquals(2 + family.length, codePointLimitEnd("가나$family", 9))
        assertEquals(3, codePointLimitEnd("\u1100\u1161\u11A8가", 3)) // NFD 각 = 3 code points, one grapheme
        assertEquals(0, codePointLimitEnd("\u1100\u1161\u11A8", 2))
    }

    @Test fun codePointUnitClipsCommittedText() {
        val state = TextFieldState("😀".repeat(41), TextRange(82))
        enforceCommittedInputLimit(state, 40, InputLimitUnit.CodePoint)
        assertEquals("😀".repeat(40), state.text.toString())
        val grapheme = TextFieldState("😀".repeat(41), TextRange(82))
        enforceCommittedInputLimit(grapheme, 40)
        assertEquals("😀".repeat(40), grapheme.text.toString()) // default unit unchanged (graphemes)
        val family = "👨‍👩‍👧" // 5 code points, 1 grapheme
        val mixed = TextFieldState("가$family", TextRange(1 + family.length))
        enforceCommittedInputLimit(mixed, 2, InputLimitUnit.CodePoint)
        assertEquals("가", mixed.text.toString()) // 1 + 5 > 2 code points: the emoji goes whole
        val korean = TextFieldState("가".repeat(41), TextRange(41))
        enforceCommittedInputLimit(korean, 40, InputLimitUnit.CodePoint)
        assertEquals(40, korean.text.length)
    }
}
