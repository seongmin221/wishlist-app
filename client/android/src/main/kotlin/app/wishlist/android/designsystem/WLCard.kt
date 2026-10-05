package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box

/** 카드 면(모서리 l 28, 카드색). 시트·확인창 위에서는 묶음 면(`sheetField`)을 쓴다. onClick이 있으면 누를 수 있다. */
@Composable
fun WLCard(
    modifier: Modifier = Modifier,
    radius: Dp = WishlistTokens.Radius.l,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalWLColors.current
    val color = if (LocalWLOnSheet.current) c.sheetField else c.card
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) { content() }
}
