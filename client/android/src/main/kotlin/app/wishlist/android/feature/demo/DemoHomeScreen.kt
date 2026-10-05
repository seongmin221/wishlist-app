package app.wishlist.android.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.PriceText
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLButtonPair
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLChip
import app.wishlist.android.designsystem.WLInput
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.OverlayHostState
import app.wishlist.android.designsystem.overlay.WLDialogSpec
import app.wishlist.android.designsystem.overlay.WLMenuItem
import app.wishlist.android.designsystem.overlay.WLSheetHeader
import app.wishlist.android.designsystem.overlay.wlAnchor
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLScrollToTopEffect
import app.wishlist.android.navigation.WLSharedSurfaceSource
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.navigation.wlSharedPhoto

/** 홈 탭 데모(debug 빌드): overlay 열기, 사진 카드·칩·목적 카드 push, 가장 긴 목적 이름과 버튼 쌍. */
@Composable
fun DemoHomeScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val scroll = rememberScrollState()
    WLScrollToTopEffect(WLTab.Home, scroll)
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(scroll)
            .padding(horizontal = WishlistTokens.Space.screenMargin),
    ) {
        DemoTabHeader("홈", "데모 · 화면 이동과 overlay") {
            DemoCircleButton(
                "더 보기",
                onClick = { overlay?.showMenu(menuAnchor, demoMenuItems(overlay)) },
                modifier = Modifier.wlAnchor { menuAnchor = it },
            ) { MoreDots() }
        }

        DemoSectionLabel("overlay")
        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            WLButton("시트 열기", WLButtonKind.Secondary, { overlay?.showSheet { DemoSheet(overlay) } }, Modifier.weight(1f))
            WLButton("확인창 열기", WLButtonKind.Primary, { overlay?.showDialog(demoDeleteDialog()) }, Modifier.weight(1f))
        }

        DemoSectionLabel("사진 카드 → 상세 (사진이 커짐)")
        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            listOf(0, 1).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16)) {
                    DemoContent.products.filterIndexed { i, _ -> i % 2 == col }
                        .forEach { DemoProductCard(it, sourceKey = "home/product/${it.id}") }
                }
            }
        }

        DemoSectionLabel("사진 없는 칩 → 목록 (면이 커짐)")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
        ) {
            DemoContent.chips.take(5).forEach { DemoSurfaceChip(it, sourceKey = "home/chip/${it.id}") }
        }

        DemoSectionLabel("비교 중인 목적")
        Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            DemoContent.purposes.filter { it.id in setOf("commute", "trail", "longest") }.forEach { p ->
                val key = "home/purpose/${p.id}"
                WLSharedSurfaceSource(key, p.color.face, WishlistTokens.Radius.xl, Modifier.fillMaxWidth()) {
                    DemoPurposeRow(p) { nav.push(WLRoute.DemoDetail(DemoIds.purpose(p.id), hasPhoto = false), key) }
                }
            }
        }

        DemoSectionLabel("가장 긴 목적 이름 · 큰 글자")
        WLCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(WishlistTokens.Space.s20), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s16)) {
                WLText(DemoContent.LONGEST_PURPOSE_NAME, WLType.display28TwoLine)
                WLText(DemoContent.LONGEST_PURPOSE_NAME, WLType.display20TwoLine)
                WLButtonPair("취소", "목적 만들기", WLButtonKind.Primary, onCancel = {}, onPrimary = {})
            }
        }
        TabBarSpacer()
    }
}

@Composable
internal fun DemoProductCard(product: DemoProduct, sourceKey: String) {
    val nav = LocalWLNavigator.current
    val c = LocalWLColors.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4),
    ) {
        WLCard(
            Modifier.fillMaxWidth(),
            radius = WishlistTokens.Radius.m,
            onClick = { nav.push(WLRoute.DemoDetail(DemoIds.product(product.id), hasPhoto = true), sourceKey) },
        ) {
            DemoPhoto(product.tint, Modifier.fillMaxWidth().aspectRatio(1f / product.photoRatio).wlSharedPhoto(sourceKey))
        }
        Spacer(Modifier.height(WishlistTokens.Space.s4))
        WLText(product.brand, WLType.label, color = c.textSecondary)
        WLText(product.name, WLType.bodyStrong, maxLines = 2)
        PriceText(product.price, product.currency, style = WLType.price.copy(fontSize = WLType.body.fontSize))
    }
}

@Composable
internal fun DemoSurfaceChip(chip: DemoChip, sourceKey: String) {
    val nav = LocalWLNavigator.current
    WLSharedSurfaceSource(sourceKey, LocalWLColors.current.chip, 20.dp) {
        WLChip(chip.name, onClick = { nav.push(WLRoute.DemoDetail(DemoIds.chip(chip.id), hasPhoto = false), sourceKey) }, count = chip.count)
    }
}

/** 홈의 비교 중인 목적 카드(목적 색 면, 도현 20). */
@Composable
private fun DemoPurposeRow(p: DemoPurpose, onClick: () -> Unit) {
    WLCard(Modifier.fillMaxWidth(), radius = WishlistTokens.Radius.xl, onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.color.face)
                .heightIn(min = 72.dp)
                .padding(horizontal = WishlistTokens.Space.s20, vertical = WishlistTokens.Space.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s4)) {
                WLText(p.name, WLType.display20TwoLine, color = WishlistTokens.Purpose.onPurpose)
                WLText("후보 ${p.candidates}", WLType.label, color = WishlistTokens.Purpose.onPurpose)
            }
        }
    }
}

@Composable
private fun DemoSheet(overlay: OverlayHostState) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20)) {
        WLSheetHeader("시트 데모", onClose = { overlay.dismiss() })
        WLInput(name, { name = it }, label = "이름", placeholder = DemoContent.LONGEST_PURPOSE_NAME, maxLength = 40)
        // 확인창의 확인이 아래 시트까지 닫는 경로(dismissAll).
        WLButton(
            "삭제(시트까지 닫기)",
            WLButtonKind.Danger,
            { overlay.showDialog(demoDeleteDialog().copy(onConfirm = { overlay.dismissAll() })) },
            Modifier.fillMaxWidth(),
        )
        WLButtonPair("취소", "저장", WLButtonKind.Primary, onCancel = { overlay.dismiss() }, onPrimary = { overlay.dismiss() })
    }
}

internal fun demoDeleteDialog() = WLDialogSpec(
    title = "상품을 삭제할까요?",
    bullets = listOf("'${DemoContent.LONGEST_PURPOSE_NAME}' 목적의 비교 후보에서도 빠져요.", "삭제한 상품은 되돌릴 수 없어요."),
    cancelText = "취소",
    confirmText = "삭제",
    confirmKind = WLButtonKind.Danger,
    onConfirm = {},
)

internal fun demoMenuItems(overlay: OverlayHostState) = listOf(
    WLMenuItem("편집") {},
    WLMenuItem("삭제") { overlay.showDialog(demoDeleteDialog()) },
)
