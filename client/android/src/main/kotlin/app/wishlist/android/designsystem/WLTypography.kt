package app.wishlist.android.designsystem

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R

/** Do Hyeon (OFL) — 목적 이름과 앱의 목소리에만 쓴다(디자인 결정 2026-10-02). */
val DoHyeon = FontFamily(Font(R.font.do_hyeon, FontWeight.Normal))

/** IBM Plex Sans KR 400·500·700 (OFL) — 도현을 뺀 모든 글자. */
val PlexSansKr = FontFamily(
    Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_kr_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_kr_bold, FontWeight.Bold),
)

/**
 * 두 플랫폼 공통 스타일 이름: display28, display20, title, body, bodyStrong, label, price, button.
 * px 값은 보드 HTML(design/handoff/screens/boards)의 해당 요소 값이다. Compose의 sp는 dp와 같은 크기이고
 * 시스템 글자 크기에 따라 커진다.
 *
 * 줄 높이: `LineHeightStyle(Alignment.Center, Trim.None)`으로 글자를 줄 상자 가운데에 두고,
 * `includeFontPadding = false`로 플랫폼이 더하는 위아래 여백을 없애 줄 높이가 보드의 line-height 그대로 나오게 한다.
 */
object WLType {
    private val centered = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

    private fun doHyeon(size: Int, lineHeightEm: Float) = TextStyle(
        fontFamily = DoHyeon,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = lineHeightEm.em,
        lineHeightStyle = centered,
        platformStyle = noFontPadding,
    )

    private fun plex(
        size: Int,
        weight: FontWeight,
        lineHeightEm: Float,
        tabular: Boolean = false,
    ) = TextStyle(
        fontFamily = PlexSansKr,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeightEm.em,
        letterSpacing = 0.sp,
        lineHeightStyle = centered,
        platformStyle = noFontPadding,
        fontFeatureSettings = if (tabular) "tnum" else null,
    )

    /** 도현 28 · 한 줄 1.0. 출처: FPurposeHomeL h1 / 목적 카드 이름(28px, line-height 1.0). */
    val display28 = doHyeon(28, 1.0f)

    /** 도현 28 · 두 줄 이상 1.2(디자인 결정 2026-10-02 행간 규칙). 이름이 두 줄로 넘어가는 자리에 쓴다. */
    val display28TwoLine = doHyeon(28, 1.2f)

    /**
     * 도현 28 · 그 자리 편집 칸 전용. 한글 아래 끝 ↔ 밑줄 3px 규칙(디자인 결정 2026-10-04)에 따라
     * 도현 28의 한글 아래 여백 5px를 줄여 줄 높이를 24sp로 둔다. 편집 칸은 한 줄이므로 `display28`과 위치가 같다.
     * (아래 패딩 보정은 Task의 편집 칸 컴포넌트에서 한다.)
     */
    val display28Edit = display28.copy(lineHeight = 24.sp)

    /** 도현 20 · 한 줄 1.0. 출처: FHomeL 비교 중인 목적 카드 이름(20px, line-height 1.0). */
    val display20 = doHyeon(20, 1.0f)

    /** 도현 20 · 두 줄 1.2. */
    val display20TwoLine = doHyeon(20, 1.2f)

    /** Plex 700 22. 출처: FCategoryListL h1(22px/700). 시트 제목 20/700(FPurposeHomeL h2)은 이 스타일에서 크기만 줄여 쓴다. */
    val title = plex(22, FontWeight.Bold, 1.3f)

    /** Plex 400 14. 출처: FCategoryListL 상품명(14px, line-height 1.35). */
    val body = plex(14, FontWeight.Normal, 1.35f)

    /** Plex 500 14. 출처: FHomeL 상품 도메인 줄(14px/500). */
    val bodyStrong = plex(14, FontWeight.Medium, 1.35f)

    /** Plex 500 12 · 자간 0. 출처: FCategoryListL 메타 줄(12px/500). 보드에 line-height가 없어 1.35로 둔다. */
    val label = plex(12, FontWeight.Medium, 1.35f)

    /** Plex 700 18 tabular. 출처: FCategoryListL 상품 가격(18px/700, tabular-nums, line-height 1). */
    val price = plex(18, FontWeight.Bold, 1.0f, tabular = true)

    /** Plex 700 16. 출처: FPurposeHomeL 만들기·삭제 버튼(16px/700). */
    val button = plex(16, FontWeight.Bold, 1.25f)
}
