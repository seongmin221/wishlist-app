package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 누를 수 없는 버튼·메뉴 항목·링크를 흐리게 그리는 불투명도. */
const val DISABLED_ALPHA = 0.4f

enum class WLButtonKind { Primary, Secondary, Danger }

/** 높이 52 pill. 큰 글자 크기에서는 늘어난다. Primary=반전색, Secondary=카드색(시트·확인창 위는 sheetField), Danger=위험 빨강(확인창 안에서만). */
@Composable
fun WLButton(
    text: String,
    kind: WLButtonKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalWLColors.current
    val onSheet = LocalWLOnSheet.current
    val (bg, fg) = when (kind) {
        WLButtonKind.Primary -> c.text to c.onInverse
        WLButtonKind.Secondary -> (if (onSheet) c.sheetField else c.card) to c.text
        WLButtonKind.Danger -> WishlistTokens.Danger.surface to WishlistTokens.Danger.onSurface
    }
    val style = if (kind == WLButtonKind.Secondary) WLType.button.copy(fontWeight = FontWeight.Medium) else WLType.button
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = WishlistTokens.Space.s20, vertical = WishlistTokens.Space.s12),
        contentAlignment = Alignment.Center,
    ) {
        WLText(text, style, color = fg, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 취소 왼쪽, 주 동작 오른쪽(더 넓다: weight 1 : 1.4). */
@Composable
fun WLButtonPair(
    cancelText: String,
    primaryText: String,
    primaryKind: WLButtonKind,
    onCancel: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLButton(cancelText, WLButtonKind.Secondary, onCancel, Modifier.weight(1f))
        WLButton(primaryText, primaryKind, onPrimary, Modifier.weight(1.4f))
    }
}
