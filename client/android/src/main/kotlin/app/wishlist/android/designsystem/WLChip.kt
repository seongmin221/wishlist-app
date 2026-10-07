package app.wishlist.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 알약 칩(높이 40). 선택됨=반전색 면 + 700 글자(체크 없음). `count`는 이름 뒤 보조색 숫자. */
@Composable
fun WLChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    count: Int? = null,
) {
    val c = LocalWLColors.current
    val bg = when {
        selected -> c.text
        LocalWLOnSheet.current -> c.sheetField
        else -> c.chip
    }
    val fg = if (selected) c.onInverse else c.text
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .background(bg)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        WLText(text, if (selected) WLType.body.copy(fontWeight = FontWeight.Bold) else WLType.body, color = fg, maxLines = 1)
        if (count != null) {
            WLText(count.toString(), WLType.price.copy(fontSize = WLType.body.fontSize), color = if (selected) fg else c.textSecondary, maxLines = 1)
        }
    }
}

/** 점선 "+ 추가" 칩(1.5dp 점선 테두리, + 아이콘 16·선 2, 간격 6). 출처: FCategoryHomeL 추가 칩. */
@Composable
fun WLAddChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWLColors.current
    val color = c.textSecondary
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(16.dp)) {
            // 보드 SVG `M12 5v14M5 12h14`(24 격자, 선 2)를 16 크기로 그린다.
            val k = size.width / 24f
            val w = 2f * k
            drawLine(color, Offset(12f * k, 5f * k), Offset(12f * k, 19f * k), w)
            drawLine(color, Offset(5f * k, 12f * k), Offset(19f * k, 12f * k), w)
        }
        WLText(text, WLType.body, color = color, maxLines = 1)
    }
}
