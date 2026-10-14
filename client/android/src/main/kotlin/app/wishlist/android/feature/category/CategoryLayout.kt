package app.wishlist.android.feature.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.WLTabBarHeight
import app.wishlist.android.navigation.wlTabBarBottomPadding

/** 보조 글 13/500(보드 머리 부제·섹션 이름·레일 아래 안내). */
internal val CategoryCaption: TextStyle = WLType.label.copy(fontSize = 13.sp)

/** 탭 바가 있는 화면의 내용 끝 여백(탭 바 + 아래 여백 + 16). 보드 padding-bottom 120~140 자리. */
@Composable
internal fun tabBarClearance(): Dp = WLTabBarHeight + wlTabBarBottomPadding() + WishlistTokens.Space.s16

/**
 * 어절·가운뎃점 뒤에서만 줄을 바꾸게 한다(보드 레일의 `word-break: keep-all` + `·` 뒤 zero-width space).
 * 한글 사이에 WORD JOINER(U+2060)를 넣어 글자 중간 줄바꿈을 막고, `·` 뒤에 ZERO WIDTH SPACE(U+200B)를 넣는다.
 */
internal fun keepAllBreaks(text: String): String = buildString {
    text.forEachIndexed { i, ch ->
        append(ch)
        val next = text.getOrNull(i + 1) ?: return@forEachIndexed
        when {
            ch == '·' -> append('​')
            ch != ' ' && next != ' ' && next != '·' -> append('⁠')
        }
    }
}

/** Skeleton 막대(카드색, 움직임 없음): 칩·이름·가격 자리. */
@Composable
internal fun SkeletonBar(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
            .background(LocalWLColors.current.card),
    )
}

/** 1dp 구분선(보드 border-top 1px line). */
@Composable
internal fun CategoryDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalWLColors.current.line))
}
