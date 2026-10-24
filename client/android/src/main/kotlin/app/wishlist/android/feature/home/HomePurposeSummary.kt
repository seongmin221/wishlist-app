package app.wishlist.android.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.detail.ProductPhoto
import app.wishlist.android.feature.purpose.PurposeDetailRoute
import app.wishlist.android.feature.purpose.purposeActivityText
import app.wishlist.android.feature.purpose.purposeColor
import app.wishlist.android.feature.purpose.purposeIcon
import app.wishlist.shared.model.PurposeSummary
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLTab

/** 보드 미리보기 얼굴: 44(모서리 14, 목적 색 테두리 2), 다음 얼굴과 16 겹침. */
private val AvatarSize = 44.dp
private val AvatarOverlap = 16.dp

/**
 * FHome "비교 중인 목적": 머리 13/500 + 오른쪽 "목적 전체"(→ 목적 탭) + HOME-01 `recentPurposes` 최대 3개(서버 순서, 빈 목적
 * 포함). 카드: 목적 색 면(모서리 28) · 후보 미리보기 얼굴 최대 4(사진 없으면 자리표시, 후보가 없으면 목적 아이콘 타일) ·
 * 도현 20 이름 · "후보 n · 활동" 12/500 · 오른쪽 화살표. 카드를 누르면 목적 상세(C6 route).
 */
@Composable
internal fun HomePurposeSummary(purposes: List<PurposeSummary>) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    Column(Modifier.padding(top = WishlistTokens.Space.s32), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            WLText(stringResource(R.string.home_purposes), HomeStyles.sectionLabel, color = c.textSecondary)
            Box(
                Modifier
                    .heightIn(min = WishlistTokens.Space.minTouch)
                    .clip(RoundedCornerShape(WishlistTokens.Radius.xs))
                    .clickable(role = Role.Button) { nav.selectTab(WLTab.Purpose) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { WLText(stringResource(R.string.home_purposes_all), WLType.body, color = c.textSecondary) }
        }
        purposes.take(3).forEach { purpose ->
            key(purpose.id) { HomePurposeCard(purpose) { nav.push(PurposeDetailRoute(purpose.id), "home/purpose/${purpose.id}") } }
        }
    }
}

@Composable
private fun HomePurposeCard(p: PurposeSummary, onClick: () -> Unit) {
    val face = purposeColor(p.colorKey)
    val on = WishlistTokens.Purpose.onPurpose
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WishlistTokens.Radius.l))
            .background(face)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.previews.isEmpty()) {
            WLIconTile(size = AvatarSize) { WLIcon(purposeIcon(p.iconKey)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(-AvatarOverlap)) {
                p.previews.take(4).forEach { preview -> Avatar(preview.imageUrl, face) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WLText(p.name, WLType.display28.copy(fontSize = 20.sp), color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WLText("${stringResource(R.string.purpose_candidates, p.candidateCount)} · ${purposeActivityText(p.activity)}", WLType.label,
                color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WLIcon(WLLineIcon.ChevronRight, size = 18.dp, color = on)
    }
}

/** 미리보기 얼굴 하나: 목적 색 테두리 2 안에 사진(채움, 없으면 중립 자리표시). */
@Composable
private fun Avatar(imageUrl: String?, border: Color) {
    Box(Modifier.size(AvatarSize).clip(RoundedCornerShape(WishlistTokens.Radius.s)).background(border).padding(2.dp)) {
        ProductPhoto(imageUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
    }
}
