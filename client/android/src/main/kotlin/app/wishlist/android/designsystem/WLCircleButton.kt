package app.wishlist.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 44 원형 아이콘 버튼(카드색 면): 위쪽 바의 뒤로, 홈 오른쪽 위 설정. `description`은 TalkBack 이름이다. */
@Composable
fun WLCircleButton(icon: WLLineIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(WishlistTokens.Space.minTouch)
            .clip(CircleShape)
            .background(LocalWLColors.current.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { WLIcon(icon) }
}
