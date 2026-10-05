package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 아이콘 타일. 기본 44dp·모서리 s 14·`iconTile` 면(흰 카드 위 홈 할 일 카드, 결정 2026-10-02). 목적 카드·접힌 띠는 `color = card`, 상태 타일은 상태 색을 넘긴다. */
@Composable
fun WLIconTile(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    radius: Dp = WishlistTokens.Radius.s,
    color: Color = LocalWLColors.current.iconTile,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(radius)).background(color),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
