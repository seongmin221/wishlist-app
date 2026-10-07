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
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import app.wishlist.android.designsystem.overlay.rememberWLMenuAnchor
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLScrollToTopEffect
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
    val menuAnchor = rememberWLMenuAnchor()

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
                onClick = { menuAnchor.boundsInWindow()?.let { overlay.showMenu(it, demoMenuItems(overlay)) } },
                modifier = Modifier.wlAnchor(menuAnchor),
            ) { DemoIconView(DemoIcon.More) }
        }

        DemoSectionLabel("overlay")
        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            WLButton("시트 열기", WLButtonKind.Secondary, { overlay.showSheet(title = "시트 데모") { DemoSheet(overlay) } }, Modifier.weight(1f))
            WLButton("확인창 열기", WLButtonKind.Primary, { overlay.showDialog(demoDeleteDialog()) }, Modifier.weight(1f))
        }

        DemoSectionLabel("사진 카드 → 상세 (사진이 커짐)")
        // 가장 긴 가격(KRW 1,190,000, Beoplay H95)도 가장 좁은 카드에서 한 줄이어야 한다(디자인 결정 2026-10-04).
        DemoItemGrid(
            DemoContent.items.filter { it.id in setOf("l1", "l2", "l5", "l6") },
            sourceKeyPrefix = "home/product",
            meta = { it.listMeta },
            dot = { it.purpose?.color },
        )

        DemoSectionLabel("사진 없는 칩 → 목록 (가로 밀기)")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8),
        ) {
            val digital = DemoContent.tops.first { it.name == "디지털·IT" }
            digital.types.take(5).forEach { DemoTypeChip(digital.name, it, sourceKey = "home/chip/${it.name}") }
        }

        DemoSectionLabel("비교 중인 목적")
        Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
            (DemoContent.purposes.take(2) + DemoContent.longestPurpose).forEach { p ->
                val key = "home/purpose/${p.id}"
                DemoPurposeRow(p) { nav.push(DemoRoute.Purpose(p.id), key) }
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
    val name = rememberTextFieldState()
    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s20)) {
        WLSheetHeader("시트 데모", onClose = { overlay.dismiss() })
        WLInput(name, label = "이름", placeholder = DemoContent.LONGEST_PURPOSE_NAME, maxLength = 40)
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
