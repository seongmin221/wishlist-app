package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 목적 6색. 면 색은 두 테마 같고, 라이트에서만 표시용 테두리 값을 1dp 테두리로 쓴다. */
enum class WLPurposeColor(val face: Color, val lightBorder: Color) {
    Coral(WishlistTokens.Purpose.coral, WishlistTokens.Purpose.LightBorder.coral),
    Mustard(WishlistTokens.Purpose.mustard, WishlistTokens.Purpose.LightBorder.mustard),
    Periwinkle(WishlistTokens.Purpose.periwinkle, WishlistTokens.Purpose.LightBorder.periwinkle),
    Cyan(WishlistTokens.Purpose.cyan, WishlistTokens.Purpose.LightBorder.cyan),
    Mint(WishlistTokens.Purpose.mint, WishlistTokens.Purpose.LightBorder.mint),
    Pink(WishlistTokens.Purpose.pink, WishlistTokens.Purpose.LightBorder.pink),
}

/** 목적 색 점. 상품 카드 10dp, 확인창·시트 목적 줄 12dp. */
@Composable
fun PurposeDot(color: WLPurposeColor, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val border = if (LocalWLDark.current) Modifier else Modifier.border(1.dp, color.lightBorder, CircleShape)
    Box(modifier.size(size).background(color.face, CircleShape).then(border))
}
