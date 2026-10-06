package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.PurposeDot
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.wlAnchor
import app.wishlist.android.designsystem.overlay.rememberWLMenuAnchor
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLSurfaceScreen
import app.wishlist.android.navigation.wlSharedPhoto

/** 데모 상세(debug 빌드에서만 진입). `id` 앞머리로 상품(사진)·칩 목록·목적 상세를 고른다. */
@Composable
internal fun DemoDetailScreen(route: DemoRoute.Detail, sourceKey: String?) {
    val kind = route.id.substringBefore(':')
    val ref = route.id.substringAfter(':')
    when {
        kind == "product" && route.hasPhoto -> DemoContent.product(ref)?.let { DemoProductDetail(it, sourceKey) }
        kind == "chip" -> WLSurfaceScreen(sourceKey) { DemoChipList(DemoContent.chip(ref)) }
        kind == "purpose" -> WLSurfaceScreen(sourceKey) { DemoPurposeDetail(DemoContent.purpose(ref)) }
        else -> WLSurfaceScreen(sourceKey) { DemoChipList(null) }
    }
}

@Composable
private fun DemoTopBar(withMenu: Boolean) {
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val anchor = rememberWLMenuAnchor()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = WishlistTokens.Space.s8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 화면의 뒤로 버튼도 시스템 뒤로와 같은 pop 전환을 쓴다(진행값 없는 뒤로).
        DemoCircleButton("뒤로", onClick = { nav.pop() }) { BackChevron() }
        if (withMenu) {
            DemoCircleButton(
                "더 보기",
                onClick = { anchor.boundsInWindow()?.let { overlay.showMenu(it, demoMenuItems(overlay)) } },
                modifier = Modifier.wlAnchor(anchor),
            ) { MoreDots() }
        }
    }
}

/** 상품 상세(FProductDetail): 큰 사진, 브랜드, 제품명, 가격, 카테고리·목적, 하단 고정 원본 보기. 탭 바 없음. */
@Composable
private fun DemoProductDetail(p: DemoProduct, sourceKey: String?) {
    val c = LocalWLColors.current
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WishlistTokens.Space.screenMargin),
        ) {
            DemoTopBar(withMenu = true)
            Spacer(Modifier.height(WishlistTokens.Space.s8))
            val photo = Modifier.fillMaxWidth().aspectRatio(1f)
            DemoPhoto(p.tint, if (sourceKey != null) photo.wlSharedPhoto(sourceKey) else photo)
            Spacer(Modifier.height(WishlistTokens.Space.s24))
            WLText(p.brand, WLType.bodyStrong)
            Spacer(Modifier.height(WishlistTokens.Space.s8))
            WLText(p.name, WLType.title)
            Spacer(Modifier.height(WishlistTokens.Space.s8))
            PriceText(p.price, p.currency, style = WLType.price.copy(fontSize = WLType.title.fontSize))
            Spacer(Modifier.height(WishlistTokens.Space.s4))
            WLText("2일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.", WLType.body, color = c.textSecondary)
            Spacer(Modifier.height(WishlistTokens.Space.s24))
            WLCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = WishlistTokens.Space.s16, vertical = WishlistTokens.Space.s8)) {
                    DemoInfoRow("카테고리") { WLText(p.category, WLType.bodyStrong) }
                    DemoInfoRow("목적") {
                        if (p.purpose != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PurposeDot(p.purpose.color)
                                WLText(p.purpose.name, WLType.bodyStrong)
                            }
                        } else {
                            WLText("없음", WLType.body, color = c.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(52.dp + WishlistTokens.Space.s40))
            Spacer(Modifier.navigationBarsPadding())
        }
        WLButton(
            "원본 보기",
            WLButtonKind.Primary,
            onClick = {},
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = WishlistTokens.Space.screenMargin, vertical = WishlistTokens.Space.s16),
        )
    }
}

@Composable
private fun DemoInfoRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16),
    ) {
        WLText(label, WLType.body, Modifier.weight(1f), color = LocalWLColors.current.textSecondary)
        value()
    }
}

/** 칩 → 세부 유형 상품 목록(사진 없는 이동의 다음 화면). */
@Composable
private fun DemoChipList(chip: DemoChip?) {
    val c = LocalWLColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WishlistTokens.Space.screenMargin),
    ) {
        DemoTopBar(withMenu = true)
        Spacer(Modifier.height(WishlistTokens.Space.s16))
        WLText(chip?.name ?: "목록", WLType.title)
        Spacer(Modifier.height(WishlistTokens.Space.s4))
        WLText("상품 ${chip?.count ?: 0}개", WLType.label, color = c.textSecondary)
        Spacer(Modifier.height(WishlistTokens.Space.s24))
        Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            DemoContent.products.forEach { p ->
                WLCard(Modifier.fillMaxWidth(), radius = WishlistTokens.Radius.l) {
                    Row(
                        Modifier.padding(WishlistTokens.Space.s12),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
                    ) {
                        Box(Modifier.size(64.dp)) {
                            DemoPhoto(p.tint, Modifier.fillMaxSize().clip(RoundedCornerShape(WishlistTokens.Radius.xs)))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
                            WLText(p.brand, WLType.label, color = c.textSecondary)
                            WLText(p.name, WLType.bodyStrong)
                            PriceText(p.price, p.currency, style = WLType.price.copy(fontSize = WLType.body.fontSize))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(WishlistTokens.Space.s40))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 목적 카드 → 목적 상세 머리(목적 색 면, 도현 28 두 줄). */
@Composable
private fun DemoPurposeDetail(p: DemoPurpose?) {
    val c = LocalWLColors.current
    val face = p?.color?.face ?: c.card
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(face, RoundedCornerShape(bottomStart = WishlistTokens.Radius.xl, bottomEnd = WishlistTokens.Radius.xl))
                .padding(horizontal = WishlistTokens.Space.screenMargin)
                .padding(bottom = WishlistTokens.Space.s24),
        ) {
            DemoTopBar(withMenu = true)
            Spacer(Modifier.height(WishlistTokens.Space.s16))
            WLText(p?.name ?: "목적", WLType.display28TwoLine, color = WishlistTokens.Purpose.onPurpose)
            Spacer(Modifier.height(WishlistTokens.Space.s8))
            WLText("후보 ${p?.candidates ?: 0} · 어제 후보 추가", WLType.label, color = WishlistTokens.Purpose.onPurpose)
        }
        Column(
            Modifier.padding(WishlistTokens.Space.screenMargin),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        ) {
            DemoContent.products.take(2).forEach { pr ->
                WLCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(WishlistTokens.Space.s16), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
                        WLText(pr.brand, WLType.label, color = c.textSecondary)
                        WLText(pr.name, WLType.bodyStrong)
                        PriceText(pr.price, pr.currency)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
