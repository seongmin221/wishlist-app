package app.wishlist.android.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * 그 자리 편집 칸. 1dp 밑줄(평소 `underline` 회색, 포커스면 글자색)이 한글 아래 끝에서 3dp 아래에 그려진다.
 * `wlLineBox`와 `wlUnderline`을 올바른 순서(밑줄 바깥, 줄 상자 안쪽)로 여기서 함께 적용하므로 호출하는 쪽은 신경 쓰지 않는다.
 * `underlineColor`로 색 면 위 값(먹색 35%)을 줄 수 있다. 밑줄이 상자 아래로 나오니 부모는 clip하지 않는다.
 * `editable = false`면 같은 크기의 투명 밑줄로 보기 상태를 그려 글자 위치가 바뀌지 않는다.
 */
@Composable
fun WLUnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = WLType.display28Edit,
    editable: Boolean = true,
    placeholder: String = "",
    underlineColor: Color? = null,
    singleLine: Boolean = true,
) {
    val c = LocalWLColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val line = when {
        !editable -> Color.Transparent
        focused -> c.text
        else -> underlineColor ?: c.underline
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editable,
        enabled = editable,
        singleLine = singleLine,
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .wlUnderline(style, line)
            .wlLineBox(style),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    WLText(placeholder, style, color = c.textSecondary)
                }
                inner()
            }
        },
    )
}
