package app.wishlist.android.feature.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLPurposeColor
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.navigation.WLTabBarHeight
import app.wishlist.android.navigation.wlTabBarBottomPadding
import java.math.BigDecimal

/**
 * 보드 견본 사진의 모습(바탕·헤드폰 선·귀컵 색). 실제 앱에서는 상품 사진(이미지)이 들어갈 자리라
 * UI 색 토큰이 아니고 두 테마에서 같다(보드 라이트·다크 PNG의 사진이 같은 색이다). 값은 FCategoryListL `items`.
 */
internal enum class DemoPhotoLook(val face: Color, val line: Color, val cup: Color) {
    White(Color(0xFFFFFFFF), Color(0xFF9A9A96), Color(0xFFD8D8D4)),
    Brown(Color(0xFF7A6B5B), Color(0xFFE9DFD2), Color(0xFFA99683)),
    Gray(Color(0xFFE9E8E4), Color(0xFF7C7B77), Color(0xFFBDBCB7)),
    Green(Color(0xFF3F4B44), Color(0xFFC9D3CB), Color(0xFF7F8E84)),
}

internal data class DemoPurpose(
    val id: String,
    val name: String,
    val color: WLPurposeColor,
    val icon: DemoIcon,
    val candidates: Int,
    /** 목적 카드 펼침 메타("후보 5 · 어제 후보 추가"). */
    val meta: String,
    val description: String? = null,
)

/**
 * 상품 카드 하나(사진 모양까지 포함). 같은 상품이라도 보드마다 사진이 달라(예: AirPods Max) 카드 단위로 둔다.
 * `ratio`는 사진 세로/가로(1:1 → 1, 4:5 → 1.25, 3:4 → 4/3).
 */
internal data class DemoItem(
    val id: String,
    val brand: String,
    val model: String,
    val price: BigDecimal,
    val look: DemoPhotoLook,
    val ratio: Float,
    val purposeId: String?,
    val checked: String,
    val shop: String,
    val pending: Boolean = false,
    val currency: String = "KRW",
    val category: String = "헤드폰",
) {
    val name get() = "$brand $model"
    val purpose get() = purposeId?.let { DemoContent.purpose(it) }

    /** 카테고리 목록 메타: "출퇴근 헤드폰 · 2일 전 확인" / "목적 미지정 · 5일 전 확인". */
    val listMeta get() = "${purpose?.name ?: "목적 미지정"} · $checked 확인"

    /** 목적 후보 메타: "무신사 · 2일 전 확인". */
    val candidateMeta get() = "$shop · $checked 확인"
}

internal data class DemoType(val name: String, val count: Int)

internal data class DemoTop(val name: String, val types: List<DemoType>)

/** 보드(FCategoryHomeL·FCategoryListL·FPurposeHomeL·FPurposeDetailL·FProductDetailL)의 예시 값과 넘침 확인용 긴 이름. */
internal object DemoContent {
    /** 넘침 확인용으로 가장 긴 목적 이름(보드 예시보다 길게). 도현 28·20 줄바꿈과 큰 글자 크기를 본다. */
    const val LONGEST_PURPOSE_NAME = "주말 캠핑용 가벼운 의자와 테이블 세트 고르기"

    /** FPurposeHomeL `ps` 7개(최근 활동순). */
    val purposes = listOf(
        DemoPurpose("commute", "출퇴근 헤드폰", WLPurposeColor.Coral, DemoIcon.Music, 5, "후보 5 · 어제 후보 추가",
            description = "지하철에서 쓸 노이즈 캔슬링 헤드폰"),
        DemoPurpose("trail", "가을 트레일 러닝", WLPurposeColor.Mustard, DemoIcon.Star, 3, "후보 3 · 3일 전 후보 추가"),
        DemoPurpose("office", "홈오피스 의자", WLPurposeColor.Periwinkle, DemoIcon.Book, 2, "후보 2 · 1주 전 후보 추가"),
        DemoPurpose("camping", "캠핑 첫 장비", WLPurposeColor.Cyan, DemoIcon.Tent, 4, "후보 4 · 2주 전 후보 추가"),
        DemoPurpose("light", "거실 조명 바꾸기", WLPurposeColor.Mint, DemoIcon.Home, 3, "후보 3 · 3주 전 후보 추가"),
        DemoPurpose("gift", "엄마 생신 선물", WLPurposeColor.Pink, DemoIcon.Gift, 2, "후보 2 · 1달 전 후보 추가"),
        DemoPurpose("carrier", "여행 캐리어", WLPurposeColor.Mustard, DemoIcon.Plane, 0, "1달 전 만듦",
            description = "다음 달 출장 때 쓸 기내용 캐리어"), // 설명은 FPurposeDetailEmptyL
    )

    /** 홈 데모에만 쓰는 가장 긴 이름의 목적(후보 0개 → 목적 상세 빈 상태). */
    val longestPurpose = DemoPurpose("longest", LONGEST_PURPOSE_NAME, WLPurposeColor.Mustard, DemoIcon.Heart, 0, "1달 전 만듦")

    /** FCategoryListL `items` 8개(헤드폰). 가장 긴 가격 `KRW 1,190,000`(Beoplay H95)이 들어 있다. */
    val items = listOf(
        DemoItem("l1", "소니", "WH-1000XM6", BigDecimal(549000), DemoPhotoLook.White, 1f, "commute", "2일 전", "무신사"),
        DemoItem("l2", "보스", "QuietComfort Ultra", BigDecimal(499000), DemoPhotoLook.Brown, 1.25f, "commute", "2일 전", "보스 공식몰"),
        DemoItem("l3", "젠하이저", "MOMENTUM 4", BigDecimal(389000), DemoPhotoLook.Gray, 4f / 3f, null, "5일 전", "젠하이저"),
        DemoItem("l4", "애플", "AirPods Max", BigDecimal(769000), DemoPhotoLook.White, 1f, "commute", "1주 전", "애플"),
        DemoItem("l5", "마샬", "MAJOR V", BigDecimal(229000), DemoPhotoLook.Green, 1.25f, null, "1주 전", "29CM", pending = true),
        DemoItem("l6", "뱅앤올룹슨", "Beoplay H95", BigDecimal(1190000), DemoPhotoLook.Gray, 4f / 3f, null, "2주 전", "뱅앤올룹슨"),
        DemoItem("l7", "소니", "ULT WEAR", BigDecimal(279000), DemoPhotoLook.White, 1f, "commute", "2주 전", "11번가"),
        DemoItem("l8", "오디오테크니카", "ATH-M50x", BigDecimal(219000), DemoPhotoLook.Brown, 1.25f, null, "3주 전", "오디오테크니카"),
    )

    /** FPurposeDetailL `items` 5개(출퇴근 헤드폰 후보). 다른 목적은 앞에서부터 후보 수만큼 쓴다(데모). */
    val candidates = listOf(
        DemoItem("c1", "소니", "WH-1000XM6", BigDecimal(549000), DemoPhotoLook.White, 1f, "commute", "2일 전", "무신사"),
        DemoItem("c2", "보스", "QuietComfort Ultra", BigDecimal(499000), DemoPhotoLook.Brown, 1.25f, "commute", "2일 전", "보스 공식몰"),
        DemoItem("c3", "애플", "AirPods Max", BigDecimal(769000), DemoPhotoLook.Gray, 4f / 3f, "commute", "1주 전", "애플"),
        DemoItem("c4", "소니", "ULT WEAR", BigDecimal(279000), DemoPhotoLook.White, 1f, "commute", "2주 전", "11번가"),
        DemoItem("c5", "마샬", "MAJOR V", BigDecimal(229000), DemoPhotoLook.Green, 1.25f, "commute", "1주 전", "29CM", pending = true),
    )

    /** FCategoryHomeL `home` 8개 상위와 세부 유형. */
    val tops = listOf(
        DemoTop("패션·잡화", types("신발" to 5, "아우터" to 3, "가방" to 3, "상의" to 2, "패션 소품" to 2)),
        DemoTop("뷰티·퍼스널케어", types("스킨케어" to 2, "향수" to 2)),
        DemoTop("디지털·IT", types("헤드폰" to 8, "키보드" to 3, "카메라·액션캠" to 3, "모니터" to 2, "마우스·트랙패드" to 2,
            "웨어러블 기기" to 2, "이어폰" to 1, "태블릿" to 1, "오디오 케이블·DAC" to 2)),
        DemoTop("가구·인테리어", types("조명" to 3, "의자" to 2, "책상·테이블" to 2)),
        DemoTop("생활·주방·가전", types("주방 가전" to 3, "공기·온습도 관리" to 2, "조리 도구" to 1)),
        DemoTop("스포츠·아웃도어·여행", types("캠핑 용품" to 4, "러닝 용품" to 2, "여행 가방·캐리어" to 2, "백패킹 소품" to 1)),
        DemoTop("취미·문화·컬렉터블", types("피규어·컬렉터블" to 2, "보드게임·퍼즐" to 1, "레고" to 0)),
        DemoTop("건강·웰빙", types("수면·회복 용품" to 1)),
    )

    /** 보드 머리의 "상품 69개". */
    val productTotal = tops.sumOf { t -> t.types.sumOf { it.count } }

    private fun types(vararg t: Pair<String, Int>) = t.map { DemoType(it.first, it.second) }

    fun item(id: String) = (items + candidates).firstOrNull { it.id == id }
    fun purpose(id: String) = purposes.firstOrNull { it.id == id } ?: longestPurpose.takeIf { it.id == id }
    fun type(top: String, type: String) = tops.firstOrNull { it.name == top }?.types?.firstOrNull { it.name == type }
}

/**
 * 어절·가운뎃점 뒤에서만 줄을 바꾸게 한다(CSS `word-break: keep-all` + 보드의 `·` 뒤 zero-width space).
 * 한글 음절 사이에 WORD JOINER(U+2060)를 넣어 글자 중간 줄바꿈을 막고, `·` 뒤에 ZERO WIDTH SPACE(U+200B)를 넣는다.
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

/** 보조 글 13/500(보드 머리 부제·섹션 제목). `label`(12/500)에서 크기만 키운다. */
internal val DemoCaption: TextStyle = WLType.label.copy(fontSize = 13.sp)

/**
 * 견본 사진: 바탕 + 헤드폰 그림(보드 SVG viewBox 48, 선 3). 그림은 상자 짧은 변의 `fraction`(목록 카드 56%)만큼.
 * 사진 이동의 양쪽(카드·상세)이 같은 `fraction`이어야 마지막 프레임에서 그림 크기가 바뀌지 않는다.
 */
@Composable
internal fun DemoPhoto(look: DemoPhotoLook, modifier: Modifier = Modifier, fraction: Float = 0.56f) {
    Canvas(modifier.background(look.face)) {
        val k = size.minDimension * fraction / 48f
        val ox = (size.width - 48f * k) / 2f
        val oy = (size.height - 48f * k) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
        val stroke = Stroke(3f * k, cap = StrokeCap.Round)
        // M10 30v-6a14 14 0 0 1 28 0v6
        drawLine(look.line, p(10f, 30f), p(10f, 24f), stroke.width, StrokeCap.Round)
        drawLine(look.line, p(38f, 24f), p(38f, 30f), stroke.width, StrokeCap.Round)
        drawArc(look.line, 180f, 180f, useCenter = false, topLeft = p(10f, 10f), size = Size(28f * k, 28f * k), style = stroke)
        listOf(7f, 33f).forEach { x ->
            val cup = Size(8f * k, 12f * k)
            drawRoundRect(look.cup, p(x, 28f), cup, CornerRadius(3f * k))
            drawRoundRect(look.line, p(x, 28f), cup, CornerRadius(3f * k), style = Stroke(3f * k))
        }
    }
}

/** 화면 머리의 원형 버튼(44, 카드색). `enabled = false`면 누르기·접근성에서 빠진다(옅어져 사라진 상태). */
@Composable
internal fun DemoCircleButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(WishlistTokens.Space.minTouch)
            .clip(CircleShape)
            .background(LocalWLColors.current.card)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * 탭 첫 화면 머리(FCategoryHomeL·FPurposeHomeL): 제목(도현 28)이 위쪽 바(`WLTopBar`, 안전 영역 아래 6, 높이 56)의
 * 세로 가운데(제목 윗변 = 안전 영역 + 20). 오른쪽 버튼도 같은 바의 trailing 자리. 부제 13/500은 바 바로 아래.
 * 호출하는 쪽 칼럼이 이미 좌우 20을 주므로 바 좌우 여백은 0이다.
 */
@Composable
internal fun DemoTabHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalWLColors.current
    Column(Modifier.fillMaxWidth()) {
        WLTopBar(
            sideMargin = 0.dp,
            trailing = trailing?.let { t -> { t() } },
            title = { WLText(title, WLType.display28, maxLines = 1) },
        )
        WLText(subtitle, DemoCaption, color = c.textSecondary, maxLines = 1)
    }
}

/** 탭 바가 있는 화면의 내용 끝 여백(탭 바 + 아래 여백 + 16). 보드 padding-bottom 120~140 자리. */
@Composable
internal fun tabBarClearance(): Dp = WLTabBarHeight + wlTabBarBottomPadding() + WishlistTokens.Space.s16

/** 탭 첫 화면 내용 끝 여백: 탭 바가 가리지 않게. */
@Composable
internal fun TabBarSpacer() {
    Spacer(Modifier.height(tabBarClearance()))
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
