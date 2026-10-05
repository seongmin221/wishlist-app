package app.wishlist.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 빈 상태: 아이콘 타일(56·모서리 m 20) + 제목(18/700) + 설명, 가운데 정렬. 목록 영역 가운데에 놓는 것은 호출하는 쪽(`Modifier.fillMaxSize()` + 이 컴포넌트). */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val c = LocalWLColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = WishlistTokens.Space.s32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12, Alignment.CenterVertically),
    ) {
        WLIconTile(size = 56.dp, radius = WishlistTokens.Radius.m, icon = icon)
        WLText(title, WLType.title.copy(fontSize = 18.sp), color = c.text, textAlign = TextAlign.Center)
        WLText(description, WLType.body.copy(lineHeight = 1.5f.times(14).sp), color = c.textSecondary, textAlign = TextAlign.Center)
    }
}
