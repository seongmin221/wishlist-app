package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import java.util.regex.Pattern
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 입력칸: 테두리 없는 면(카드색, 시트·확인창 위는 sheetField)·모서리 m 20·높이 56(여러 줄이면 `minLines`로 늘어난다).
 * 위 라벨(13) · 오른쪽 보조 글자(예: "최대 40자")는 `label`·`hint`로 준다.
 */
@Composable
fun WLInput(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    require(maxLength >= 0) { "maxLength must be non-negative" }
    // InputTransformation cannot inspect the incoming composing range (buffer API is internal).
    // Observe the single field state after framework edits, and leave every IME composition intact.
    LaunchedEffect(state, maxLength) {
        snapshotFlow { state.text.toString() to state.composition }.collect {
            enforceCommittedInputLimit(state, maxLength)
        }
    }
    val c = LocalWLColors.current
    val face = if (LocalWLOnSheet.current) c.sheetField else c.card
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        if (label != null || hint != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // 라벨은 접근성에서 따로 읽고, 입력칸의 editableText 의미를 그대로 유지한다.
                WLText(label.orEmpty(), WLType.body.copy(fontSize = 13.sp), color = c.textSecondary)
                WLText(hint.orEmpty(), WLType.label, color = c.textSecondary)
            }
        }
        BasicTextField(
            state = state,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = minLines),
            textStyle = WLType.button.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            keyboardOptions = keyboardOptions,
            // Do not override contentDescription: TalkBack must announce editable text and selection.
            modifier = Modifier.fillMaxWidth(),
            decorator = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.m))
                        .background(face)
                        .padding(horizontal = 18.dp, vertical = if (singleLine) 0.dp else 16.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (state.text.isEmpty() && placeholder.isNotEmpty()) {
                        // Preserve the visible placeholder announcement when the field is empty.
                        WLText(
                            placeholder,
                            WLType.button.copy(fontWeight = FontWeight.Normal),
                            Modifier,
                            color = c.textSecondary,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private val GraphemePattern = Pattern.compile("\\X")

/** Returns a UTF-16 boundary after at most [maxLength] extended grapheme clusters. */
internal fun graphemeLimitEnd(text: CharSequence, maxLength: Int): Int {
    require(maxLength >= 0)
    if (maxLength == Int.MAX_VALUE) return text.length
    val matcher = GraphemePattern.matcher(text)
    var end = 0
    var count = 0
    while (count < maxLength && matcher.find()) {
        end = matcher.end()
        count++
    }
    return end
}

/** Programmatic edits, paste, and limit changes share the same committed-state normalization. */
internal fun enforceCommittedInputLimit(state: TextFieldState, maxLength: Int) {
    if (state.composition != null) return
    state.edit {
        val end = graphemeLimitEnd(asCharSequence(), maxLength)
        if (end < length) replace(end, length, "")
    }
}
