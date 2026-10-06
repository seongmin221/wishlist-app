package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLSharedSurfaceSource
import app.wishlist.android.navigation.WLTab

/** 목적 탭 데모: 목적 색 면 카드(도현 28) 목록. 카드 → 목적 상세는 자리 표시 면 이동. 맨 아래 가장 긴 목적 이름. */
@Composable
fun DemoPurposeScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Purpose, scroll)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(scroll)
            .padding(horizontal = WishlistTokens.Space.screenMargin),
    ) {
        DemoTabHeader("목적", "비교 중 ${DemoContent.purposes.size}개 · 최근 활동순")
        Spacer(Modifier.height(WishlistTokens.Space.s24))
        Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
            DemoContent.purposes.forEach { p ->
                val key = "purpose/card/${p.id}"
                WLSharedSurfaceSource(key, p.color.face, WishlistTokens.Radius.xl, Modifier.fillMaxWidth()) {
                    WLCard(
                        Modifier.fillMaxWidth(),
                        radius = WishlistTokens.Radius.xl,
                        onClick = { nav.push(DemoRoute.Detail(DemoIds.purpose(p.id), hasPhoto = false), key) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(p.color.face)
                                .heightIn(min = 76.dp)
                                .padding(horizontal = WishlistTokens.Space.s16, vertical = WishlistTokens.Space.s16),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
                        ) {
                            WLIconTile(size = 36.dp, radius = 12.dp, color = c.card) { DemoPhoto(p.color, Modifier.fillMaxSize()) }
                            WLText(p.name, WLType.display28TwoLine, Modifier.weight(1f), color = WishlistTokens.Purpose.onPurpose)
                            WLText(p.candidates.toString(), WLType.price.copy(fontSize = WLType.display20.fontSize),
                                color = WishlistTokens.Purpose.onPurpose)
                        }
                    }
                }
            }
        }
        TabBarSpacer()
    }
}
