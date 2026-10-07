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
}
