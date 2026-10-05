package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLAddChip
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLTab

/** 카테고리 탭 데모: 왼쪽 상위 레일 + 오른쪽 세부 유형 알약 칩(칩 → 목록은 자리 표시 면 이동). */
@Composable
fun DemoCategoryScreen() {
    val c = LocalWLColors.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Category, scroll)
    var selected by rememberSaveable { mutableIntStateOf(2) }

    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(scroll)) {
        Box(Modifier.padding(horizontal = WishlistTokens.Space.screenMargin)) {
            DemoTabHeader("카테고리", "상품 69개")
        }
        Spacer(Modifier.height(WishlistTokens.Space.s16))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().padding(top = WishlistTokens.Space.s16)) {
            Column(Modifier.width(112.dp)) {
                DemoContent.railCategories.forEachIndexed { i, name ->
                    val on = i == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Tab) { selected = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 52.dp).background(if (on) c.text else c.background))
                        WLText(
                            name,
                            if (on) WLType.body.copy(fontWeight = FontWeight.Bold) else WLType.body,
                            Modifier.padding(start = 17.dp, end = 8.dp),
                            color = if (on) c.text else c.textSecondary,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(end = WishlistTokens.Space.screenMargin)) {
                WLText(DemoContent.railCategories[selected], WLType.label, color = c.textSecondary)
                Spacer(Modifier.height(WishlistTokens.Space.s16))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
                    verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
                ) {
                    DemoContent.chips.forEach { DemoSurfaceChip(it, sourceKey = "category/chip/${it.id}") }
                    WLAddChip("추가", onClick = {})
                }
            }
        }
        TabBarSpacer()
    }
}
