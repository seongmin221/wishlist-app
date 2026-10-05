package app.wishlist.android.feature.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLPurposeColor
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.WLTabBarHeight
import app.wishlist.android.navigation.wlTabBarBottomPadding
import java.math.BigDecimal

internal data class DemoPurpose(val id: String, val name: String, val color: WLPurposeColor, val candidates: Int)

internal data class DemoProduct(
    val id: String,
    val brand: String,
    val name: String,
    val price: BigDecimal,
    val currency: String,
    val category: String,
    val purpose: DemoPurpose?,
    val tint: WLPurposeColor,
    val photoRatio: Float,
)

internal data class DemoChip(val id: String, val name: String, val count: Int)

/** 보드(FHomeL·FCategoryHomeL·FPurposeHomeL·FProductDetailL)의 예시 값과 넘침 확인용 긴 이름. */
internal object DemoContent {
    /** 넘침 확인용으로 가장 긴 목적 이름(보드 예시보다 길게). 도현 28·20 줄바꿈과 큰 글자 크기를 본다. */
    const val LONGEST_PURPOSE_NAME = "주말 캠핑용 가벼운 의자와 테이블 세트 고르기"

    val purposes = listOf(
        DemoPurpose("commute", "출퇴근 헤드폰", WLPurposeColor.Coral, 5),
        DemoPurpose("trail", "가을 트레일 러닝", WLPurposeColor.Mustard, 3),
        DemoPurpose("office", "홈오피스 의자", WLPurposeColor.Periwinkle, 2),
        DemoPurpose("camping", "캠핑 첫 장비", WLPurposeColor.Cyan, 4),
        DemoPurpose("light", "거실 조명 바꾸기", WLPurposeColor.Mint, 3),
        DemoPurpose("gift", "엄마 생신 선물", WLPurposeColor.Pink, 2),
        DemoPurpose("longest", LONGEST_PURPOSE_NAME, WLPurposeColor.Mustard, 0),
    )

    val products = listOf(
        DemoProduct("p1", "소니", "WH-1000XM6", BigDecimal(549000), "KRW", "헤드폰", purposes[0], WLPurposeColor.Coral, 1f),
        DemoProduct("p2", "보스", "QuietComfort Ultra", BigDecimal(499000), "KRW", "헤드폰", purposes[0], WLPurposeColor.Periwinkle, 1.25f),
        DemoProduct("p3", "살로몬", "Speedcross 6", BigDecimal("159.99"), "USD", "러닝화", purposes[1], WLPurposeColor.Mustard, 1.25f),
        DemoProduct("p4", "헬리녹스", "체어 원 라이트", BigDecimal(139000), "KRW", "캠핑 의자", purposes[6], WLPurposeColor.Mint, 1f),
    )

    val railCategories = listOf("패션·잡화", "뷰티·퍼스널케어", "디지털·IT", "가구·인테리어", "생활·주방·가전", "스포츠·아웃도어·여행")

    val chips = listOf(
        DemoChip("headphone", "헤드폰", 8), DemoChip("keyboard", "키보드", 3), DemoChip("camera", "카메라·액션캠", 3),
        DemoChip("monitor", "모니터", 2), DemoChip("mouse", "마우스·트랙패드", 2), DemoChip("wearable", "웨어러블 기기", 2),
        DemoChip("earphone", "이어폰", 1), DemoChip("tablet", "태블릿", 1), DemoChip("audio", "오디오 케이블·DAC", 2),
    )

    fun product(id: String) = products.firstOrNull { it.id == id }
    fun chip(id: String) = chips.firstOrNull { it.id == id }
    fun purpose(id: String) = purposes.firstOrNull { it.id == id }
}

/** 데모 상세 id 규칙: `product:p1`, `chip:headphone`, `purpose:commute`. */
internal object DemoIds {
    fun product(id: String) = "product:$id"
    fun chip(id: String) = "chip:$id"
    fun purpose(id: String) = "purpose:$id"
}

/** 사진 대신 쓰는 면(목적 색 + 헤드폰 선). 목적 색은 두 테마 같다. */
@Composable
internal fun DemoPhoto(tint: WLPurposeColor, modifier: Modifier = Modifier) {
    val line = WishlistTokens.Purpose.onPurpose.copy(alpha = 0.55f)
    Canvas(modifier.background(tint.face)) {
        val w = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = w * 0.26f
        drawArc(line, 180f, 180f, useCenter = false, topLeft = Offset(cx - r, cy - r * 0.9f), size = Size(r * 2, r * 2),
            style = Stroke(w * 0.06f, cap = StrokeCap.Round))
        val cup = Size(w * 0.14f, w * 0.24f)
        listOf(cx - r - cup.width / 2f, cx + r - cup.width / 2f).forEach { x ->
            drawRoundRect(line, Offset(x, cy), cup, CornerRadius(cup.width / 2f))
        }
    }
}

/** 화면 머리의 원형 버튼(44, 카드색). */
@Composable
internal fun DemoCircleButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Box(
        modifier
            .size(WishlistTokens.Space.minTouch)
            .clip(CircleShape)
            .background(LocalWLColors.current.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
internal fun BackChevron(color: Color = LocalWLColors.current.text) {
    Canvas(Modifier.size(20.dp)) {
        val k = size.width / 24f
        val s = Stroke(2f * k, cap = StrokeCap.Round)
        drawLine(color, Offset(15f * k, 5f * k), Offset(8f * k, 12f * k), s.width, StrokeCap.Round)
        drawLine(color, Offset(8f * k, 12f * k), Offset(15f * k, 19f * k), s.width, StrokeCap.Round)
    }
}

@Composable
internal fun MoreDots(color: Color = LocalWLColors.current.text) {
    Canvas(Modifier.size(20.dp)) {
        val r = size.width * 0.08f
        listOf(0.25f, 0.5f, 0.75f).forEach { drawCircle(color, r, Offset(size.width * it, size.height / 2f)) }
    }
}

/** 탭 첫 화면 머리: 도현 28 제목 + 보조 글. */
@Composable
internal fun DemoTabHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalWLColors.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = WishlistTokens.Space.s24),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            WLText(title, WLType.display28)
            Spacer(Modifier.height(WishlistTokens.Space.s8))
            WLText(subtitle, WLType.label, color = c.textSecondary)
        }
        if (trailing != null) trailing()
    }
}

/** 탭 첫 화면 내용 끝 여백: 탭 바가 가리지 않게. */
@Composable
internal fun TabBarSpacer() {
    Spacer(Modifier.height(WLTabBarHeight + wlTabBarBottomPadding() + WishlistTokens.Space.s16))
}

@Composable
internal fun DemoSectionLabel(text: String, modifier: Modifier = Modifier) {
    WLText(
        text,
        WLType.label,
        modifier.padding(top = WishlistTokens.Space.s32, bottom = WishlistTokens.Space.s12),
        color = LocalWLColors.current.textSecondary,
    )
}
