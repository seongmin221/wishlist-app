package app.wishlist.android.designsystem

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * WLType 스타일로 글자를 그린다. `wlLineBox`를 항상 맨 뒤에 붙여 CSS line-height와 같은 상자 높이를 만든다
 * (WLTypography.kt 규칙을 호출하는 쪽이 틀리지 않게 여기서 처리).
 */
@Composable
fun WLText(
    text: String,
    style: TextStyle = WLType.body,
    modifier: Modifier = Modifier,
    color: Color = LocalWLColors.current.text,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier.wlLineBox(style),
        style = style.copy(color = color, textAlign = textAlign ?: TextAlign.Unspecified),
        maxLines = maxLines,
        overflow = overflow,
    )
}
