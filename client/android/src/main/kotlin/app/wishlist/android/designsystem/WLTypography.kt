package app.wishlist.android.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * 줄 높이 (2026-10-05 에뮬레이터 API 36 실측): Compose는 `lineHeight`를 줄과 줄 사이에만 정확히 적용하고,
 * `Trim.Both`면 첫 줄 위·마지막 줄 아래는 글꼴 자연 높이(도현 28이면 28dp)로 고정한다. 그래서 N줄 상자 높이는
 * `자연 높이 + (N-1) x lineHeight`이고, 한 줄 상자는 lineHeight가 24든 40이든 자연 높이로 남는다
 * (Trim.None이면 lineHeight가 자연 높이보다 클 때만 커지고 작을 때는 줄지 않는다).
 * 보드의 CSS line-height(N x lineHeight)와 맞추려고 `LineHeightStyle(Center, Trim.Both)`와 `includeFontPadding = false`를 쓰고
 * 바깥 상자 높이는 `Modifier.wlLineBox(style)`가 `lineHeight - 자연 높이`만큼 보정한다(글자도 그 절반만큼 이동, CSS half-leading).
 * 이 스타일로 그리는 Text에는 항상 `Modifier.wlLineBox(WLType.x)`를 붙이되, modifier 체인에서 Text에 가장 가깝게(맨 뒤에) 둔다.
 * 배경·밑줄·padding 같은 modifier는 그 앞에 둬야 보정된 상자 크기를 쓴다.
 */
object WLType {
    private val centered = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
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
     * (아래 패딩 보정은 편집 칸 컴포넌트에서 한다.)
     */
    val display28Edit = display28.copy(lineHeight = 24.sp)

    /** 도현 20 · 한 줄 1.0. 출처: FHomeL 비교 중인 목적 카드 이름(20px, line-height 1.0). */
    val display20 = doHyeon(20, 1.0f)

    /** 도현 20 · 두 줄 1.2. */
    val display20TwoLine = doHyeon(20, 1.2f)

    /** Plex 700 22. 출처: FCategoryListL h1(22px/700). 시트 제목 20/700(FPurposeHomeL h2)은 이 스타일에서 크기만 줄여 쓴다. 줄 높이 1.3은 보드에 없어 고른 값이다. */
    val title = plex(22, FontWeight.Bold, 1.3f)

    /** Plex 400 14. 출처: FCategoryListL 상품명(14px, line-height 1.35). */
    val body = plex(14, FontWeight.Normal, 1.35f)

    /** Plex 500 14. 출처: FHomeL 상품 도메인 줄(14px/500). */
    val bodyStrong = plex(14, FontWeight.Medium, 1.35f)

    /** Plex 500 12 · 자간 0. 출처: FCategoryListL 메타 줄(12px/500). 보드에 line-height가 없어 1.35로 둔다. */
    val label = plex(12, FontWeight.Medium, 1.35f)

    /**
     * Plex 700 18 tabular. IBM Plex Sans KR은 GSUB에 `tnum`이 없지만 숫자 0-9가 기본으로 모두 advance 600/1000이라
     * 이미 고정폭이다(hmtx 확인). "tnum"은 안전장치일 뿐 무해하다. Do Hyeon 숫자는 비례폭이지만 가격에 쓰지 않는다.
     * 출처: FCategoryListL 상품 가격(18px/700, tabular-nums, line-height 1). */
    val price = plex(18, FontWeight.Bold, 1.0f, tabular = true)

    /** Plex 700 16. 출처: FPurposeHomeL 만들기·삭제 버튼(16px/700). 줄 높이 1.25는 보드에 없어 고른 값이다. */
    val button = plex(16, FontWeight.Bold, 1.25f)
}

/**
 * 글꼴 메트릭(em 단위, units-per-em 1000). 2026-10-05 Python 3.9 표준 라이브러리로 TTF의 hhea/glyf를 직접 읽어 구했다.
 * - ascent/descent: hhea ascender 와 |descender|. 브라우저(보드)와 Compose(includeFontPadding=false)가 쓰는 값이고 lineGap은 쓰지 않는다.
 * - hangulDepth: 한글 음절 아래 끝이 기준선 아래로 내려가는 깊이(glyf yMin의 절댓값). 11172자 음절 전수 조사 결과
 *   Do Hyeon: 받침 있는 글자 대부분이 -23(=0.023), 받침 없는 글자는 +82(기준선 위)라 가장 깊은 흔한 값 0.023을 쓴다.
 *   Plex Regular/Medium/Bold: 중앙값 -143/-148/-155(최소 -166/-173/-181).
 */
private class WLFontMetrics(val ascent: Float, val descent: Float, val hangulDepth: Float)

private val DoHyeonMetrics = WLFontMetrics(0.8f, 0.2f, 0.023f)
private val PlexRegularMetrics = WLFontMetrics(1.085f, 0.415f, 0.143f)
private val PlexMediumMetrics = WLFontMetrics(1.085f, 0.415f, 0.148f)
private val PlexBoldMetrics = WLFontMetrics(1.085f, 0.415f, 0.155f)

private fun TextStyle.metrics(): WLFontMetrics = when {
    fontFamily == DoHyeon -> DoHyeonMetrics
    fontWeight == FontWeight.Bold -> PlexBoldMetrics
    fontWeight == FontWeight.Medium -> PlexMediumMetrics
    else -> PlexRegularMetrics
}

/** 밑줄 윗면과 한글 아래 끝 사이 거리(디자인 결정 2026-10-04). */
val WLUnderlineGap: Dp = 3.dp

/** 줄 높이(px). sp는 API 34+의 비선형 글자 크기 배율 때문에 값마다 따로 px로 바꿔야 한다(합·차를 sp로 계산한 뒤 바꾸면 틀린다). */
private fun TextStyle.lineHeightPx(density: Density): Float = with(density) {
    if (lineHeight.isEm) lineHeight.value * fontSize.toPx() else lineHeight.toPx()
}

/** 글꼴 자연 높이(px) = (ascent+descent) x 글자 크기. */
private fun TextStyle.naturalHeightPx(density: Density): Float =
    with(density) { (metrics().ascent + metrics().descent) * fontSize.toPx() }

/**
 * 한글 아래 끝에서 줄 상자 아래까지의 거리(px). 줄 높이와 글꼴 내용 높이(ascent+descent)의 차이 절반이
 * 위아래로 나뉜다(CSS half-leading, `LineHeightStyle.Alignment.Center`와 같은 모델).
 * 글자 크기 배율(fontScale, 비선형 포함)은 `density`가 반영한다.
 * 예(배율 1): 도현 28 줄 높이 28 -> 4.96dp, 줄 높이 24 -> 2.96dp(=밑줄 3dp 규칙).
 */
fun TextStyle.hangulBottomGapPx(density: Density): Float {
    val m = metrics()
    return (lineHeightPx(density) - naturalHeightPx(density)) / 2f +
        (m.descent - m.hangulDepth) * with(density) { fontSize.toPx() }
}

/**
 * 텍스트 상자를 CSS line-height와 같게 만든다: 높이 = N x lineHeight. Compose가 만드는 높이(자연 높이 + (N-1) x lineHeight)에
 * `lineHeight - 자연 높이(=(ascent+descent) x size)`를 더하고 글자를 그 절반만큼 내려(올려) 놓는다.
 * 한 줄·여러 줄 모두 같은 보정값이다. 글자가 상자 밖으로 나올 수 있으므로 부모가 clip하지 않아야 한다.
 */
fun Modifier.wlLineBox(style: TextStyle): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val extra = (style.lineHeightPx(this) - style.naturalHeightPx(this)).roundToInt()
    layout(placeable.width, constraints.constrainHeight((placeable.height + extra).coerceAtLeast(0))) {
        placeable.place(0, extra shr 1)
    }
}

/**
 * 그 자리 편집 칸(WLUnderlineField)용 밑줄. 텍스트 상자 아래에 `thickness` 두께로, 윗면이 한글 아래 끝에서 3dp 떨어지게 그린다.
 * 줄 높이가 24인 `WLType.display28Edit`는 한글 아래 여백이 2.96이라 밑줄이 상자 바로 아래에 붙는다.
 * `wlLineBox(style)` 앞에(체인 바깥쪽에) 붙인다: `Modifier.wlUnderline(style, color).wlLineBox(style)`. 상자 밖(아래)에 그리므로 부모가 clip하면 안 된다.
 */
fun Modifier.wlUnderline(style: TextStyle, color: Color, thickness: Dp = 1.dp): Modifier =
    drawBehind {
        val top = size.height + (WLUnderlineGap.toPx() - style.hangulBottomGapPx(this))
        drawRect(color, Offset(0f, top), Size(size.width, thickness.toPx()))
    }
