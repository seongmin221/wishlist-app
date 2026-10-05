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
                WLText(label.orEmpty(), WLType.body.copy(fontSize = 13.sp), color = c.textSecondary)
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
            modifier = Modifier.fillMaxWidth(),
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
                        WLText(placeholder, WLType.button.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
                    }
                    inner()
                }
            },
        )
    }
}
