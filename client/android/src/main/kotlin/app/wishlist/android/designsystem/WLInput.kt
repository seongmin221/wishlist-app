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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 입력칸: 테두리 없는 면(카드색, 시트·확인창 위는 sheetField)·모서리 m 20·높이 56(여러 줄이면 `minLines`로 늘어난다).
 * 위 라벨(13) · 오른쪽 보조 글자(예: "최대 40자")는 `label`·`hint`로 준다.
 */
@Composable
fun WLInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = LocalWLColors.current
    val face = if (LocalWLOnSheet.current) c.sheetField else c.card
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        if (label != null || hint != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // 입력칸이 같은 라벨을 읽으므로 보이는 라벨은 접근성에서 뺀다(두 번 읽지 않게, iOS와 같음).
                WLText(label.orEmpty(), WLType.body.copy(fontSize = 13.sp), Modifier.clearAndSetSemantics { }, color = c.textSecondary)
                WLText(hint.orEmpty(), WLType.label, color = c.textSecondary)
            }
        }
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            textStyle = WLType.button.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            keyboardOptions = keyboardOptions,
            // 칸의 이름은 라벨(없으면 안내 글자). TalkBack이 라벨과 칸을 따로 읽지 않는다.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label ?: placeholder },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.m))
                        .background(face)
                        .padding(horizontal = 18.dp, vertical = if (singleLine) 0.dp else 16.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        // 라벨이 있으면 안내 글자는 칸 이름 뒤에 읽힌다(iOS 힌트). 라벨이 없으면 칸 이름이 곧 안내 글자라 뺀다.
                        WLText(
                            placeholder,
                            WLType.button.copy(fontWeight = FontWeight.Normal),
                            if (label == null) Modifier.clearAndSetSemantics { } else Modifier,
                            color = c.textSecondary,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
